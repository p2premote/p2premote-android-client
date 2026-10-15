//! Per-attempt JNI handles for application-owned WebSocket signaling.
use jni::{
    objects::{GlobalRef, JClass, JObject, JString, JValue},
    sys::{jboolean, jlong, jstring},
    JNIEnv, JavaVM,
};
use p2premote_punch::easyp2p::signal::{
    ExternalSignalSession, SendFuture, SessionIdentity, WireSender,
};
use rand::RngCore;
use serde_json::json;
use std::{
    collections::HashMap,
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc, Mutex, OnceLock,
    },
    time::{Duration, Instant},
};
use tokio::sync::oneshot;

struct Sender {
    vm: JavaVM,
    callback: GlobalRef,
    next: AtomicU64,
    pending: Mutex<HashMap<u64, oneshot::Sender<Result<(), String>>>>,
}
struct Pending<'a> {
    sender: &'a Sender,
    id: u64,
}
impl Drop for Pending<'_> {
    fn drop(&mut self) {
        self.sender.pending.lock().unwrap().remove(&self.id);
    }
}
impl Sender {
    fn complete(&self, id: u64, error: String) {
        if let Some(tx) = self.pending.lock().unwrap().remove(&id) {
            let _ = tx.send(if error.is_empty() { Ok(()) } else { Err(error) });
        }
    }
    fn cancel(&self) {
        self.pending.lock().unwrap().clear();
    }
}
impl WireSender for Sender {
    fn send(&self, data: String, deadline: Instant) -> SendFuture<'_> {
        Box::pin(async move {
            let id = self.next.fetch_add(1, Ordering::Relaxed);
            let (tx, rx) = oneshot::channel();
            {
                let mut pending = self.pending.lock().unwrap();
                if pending.len() >= 16 {
                    return Err("signal_queue_full".into());
                }
                pending.insert(id, tx);
            }
            let _pending = Pending { sender: self, id };
            let remaining = deadline
                .saturating_duration_since(Instant::now())
                .as_millis()
                .min(5000) as i64;
            if remaining <= 0 {
                return Err("signal_delivery_timeout".into());
            }
            // AttachGuard and local references must leave scope before awaiting.
            let queued = {
                let mut env = self
                    .vm
                    .attach_current_thread()
                    .map_err(|_| "signal_callback_failed".to_string())?;
                let value = env
                    .new_string(data)
                    .map_err(|_| "signal_callback_failed".to_string())?;
                let result = env
                    .call_method(
                        self.callback.as_obj(),
                        "enqueue",
                        "(JLjava/lang/String;J)Z",
                        &[
                            JValue::Long(id as i64),
                            JValue::Object(value.as_ref()),
                            JValue::Long(remaining),
                        ],
                    )
                    .and_then(|v| v.z());
                if result.is_err() {
                    let _ = env.exception_clear();
                }
                result.map_err(|_| "signal_callback_failed".to_string())?
            };
            if !queued {
                return Err("signal_queue_full".into());
            }
            match tokio::time::timeout_at(deadline.into(), rx).await {
                Ok(Ok(result)) => result,
                Ok(Err(_)) => Err("signal_cancelled".into()),
                Err(_) => Err("signal_delivery_timeout".into()),
            }
        })
    }
}
struct Entry {
    session: Arc<ExternalSignalSession>,
    sender: Arc<Sender>,
}
fn sessions() -> &'static Mutex<HashMap<String, Arc<Entry>>> {
    static SESSIONS: OnceLock<Mutex<HashMap<String, Arc<Entry>>>> = OnceLock::new();
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}
fn find(handle: &str) -> Result<Arc<Entry>, String> {
    sessions()
        .lock()
        .unwrap()
        .get(handle)
        .cloned()
        .ok_or_else(|| "invalid_signal_handle".into())
}
fn string_result(
    mut env: JNIEnv,
    body: impl FnOnce(&mut JNIEnv) -> Result<String, String>,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| body(&mut env)));
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
    }
    let output = match result {
        Ok(Ok(s)) => s,
        Ok(Err(e)) => super::err_json(e),
        Err(_) => super::err_json("signal_native_panic".into()),
    };
    match env.new_string(output) {
        Ok(s) => s.into_raw(),
        Err(e) => super::throw_and_null(env, e.to_string()),
    }
}

#[no_mangle]
pub extern "system" fn Java_top_p2premote_android_PunchNative_nativeCreateSignalSession(
    env: JNIEnv,
    _: JClass,
    context: JString,
    callback: JObject,
) -> jstring {
    string_result(env, |env| {
        let context = super::read_jstring(env, &context)?;
        let identity: SessionIdentity =
            serde_json::from_str(&context).map_err(|_| "signal_context_mismatch".to_string())?;
        let class = env.get_object_class(&callback).map_err(super::jni_err)?;
        env.get_method_id(class, "enqueue", "(JLjava/lang/String;J)Z")
            .map_err(super::jni_err)?;
        let callback = env.new_global_ref(callback).map_err(super::jni_err)?;
        let sender = Arc::new(Sender {
            vm: env.get_java_vm().map_err(super::jni_err)?,
            callback,
            next: AtomicU64::new(1),
            pending: Mutex::new(HashMap::new()),
        });
        let session =
            ExternalSignalSession::new(identity, sender.clone()).map_err(|e| e.to_string())?;
        let mut random = [0u8; 16];
        rand::rngs::OsRng.fill_bytes(&mut random);
        let handle = p2premote_punch::easyp2p::crypto::hex(&random);
        let mut registry = sessions().lock().unwrap();
        if registry.len() >= 32 {
            return Err("signal_queue_full".into());
        }
        registry.insert(handle.clone(), Arc::new(Entry { session, sender }));
        Ok(json!({"ok": true, "handle_id": handle}).to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_top_p2premote_android_PunchNative_nativeRegisterSignalCall(
    env: JNIEnv,
    _: JClass,
    handle: JString,
    call: JString,
) -> jstring {
    string_result(env, |env| {
        let entry = find(&super::read_jstring(env, &handle)?)?;
        let data: serde_json::Value = serde_json::from_str(&super::read_jstring(env, &call)?)
            .map_err(|_| "signal_context_mismatch".to_string())?;
        let id = data
            .get("call_id")
            .and_then(|v| v.as_str())
            .ok_or("signal_context_mismatch")?;
        let uid = data
            .get("token")
            .and_then(|v| v.as_str())
            .ok_or("signal_context_mismatch")?;
        let millis = data
            .get("timeout_ms")
            .and_then(|v| v.as_u64())
            .filter(|v| *v > 0 && *v <= 300_000)
            .ok_or("signal_context_mismatch")?;
        entry
            .session
            .register_call(id, uid, Duration::from_millis(millis))
            .map_err(|e| e.to_string())?;
        Ok(json!({"ok": true}).to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_top_p2premote_android_PunchNative_nativeDeliverSignal(
    env: JNIEnv,
    _: JClass,
    handle: JString,
    identity: JString,
    frame: JString,
) -> jstring {
    string_result(env, |env| {
        let entry = find(&super::read_jstring(env, &handle)?)?;
        let identity: serde_json::Value =
            serde_json::from_str(&super::read_jstring(env, &identity)?)
                .map_err(|_| "signal_context_mismatch".to_string())?;
        let connection = identity
            .get("connection_id")
            .and_then(|v| v.as_str())
            .ok_or("signal_context_mismatch")?;
        let source = identity
            .get("source_device_id")
            .and_then(|v| v.as_i64())
            .ok_or("signal_context_mismatch")?;
        entry
            .session
            .deliver_json(connection, source, &super::read_jstring(env, &frame)?)
            .map_err(|e| e.to_string())?;
        Ok(json!({"ok": true}).to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_top_p2premote_android_PunchNative_nativeCompleteSignalSend(
    mut env: JNIEnv,
    _: JClass,
    handle: JString,
    request: jlong,
    error: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Ok(entry) = super::read_jstring(&mut env, &handle).and_then(|h| find(&h)) {
            entry.sender.complete(
                request as u64,
                super::read_jstring(&mut env, &error)
                    .unwrap_or_else(|_| "signal_callback_failed".into()),
            );
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_top_p2premote_android_PunchNative_nativeExchangeWithSignal(
    env: JNIEnv,
    _: JClass,
    handle: JString,
    request: JString,
) -> jstring {
    string_result(env, |env| {
        let entry = find(&super::read_jstring(env, &handle)?)?;
        let request: p2premote_punch::ExchangeInput =
            serde_json::from_str(&super::read_jstring(env, &request)?)
                .map_err(|_| "signal_context_mismatch".to_string())?;
        let call = entry
            .session
            .lookup_call("wg-config")
            .ok_or("signal_context_mismatch")?;
        let timeout = Duration::from_secs(request.timeout_secs.max(1) as u64);
        let result = super::block_on_blocking(p2premote_punch::api::exchange_with_signal(
            request, timeout, call,
        ))??;
        serde_json::to_string(&result).map_err(|_| "signal_encode_failed".into())
    })
}

#[no_mangle]
pub extern "system" fn Java_top_p2premote_android_PunchNative_nativeStartUdpTunnelWithSignal(
    env: JNIEnv,
    _: JClass,
    handle: JString,
    call_id: JString,
    request: JString,
) -> jstring {
    string_result(env, |env| {
        let entry = find(&super::read_jstring(env, &handle)?)?;
        let id = super::read_jstring(env, &call_id)?;
        let request: p2premote_punch::UdpTunnelInput =
            serde_json::from_str(&super::read_jstring(env, &request)?)
                .map_err(|_| "signal_context_mismatch".to_string())?;
        let call = entry
            .session
            .lookup_call(&id)
            .ok_or("signal_context_mismatch")?;
        let budget = Duration::from_secs(request.timeout_secs.max(1) as u64 + 10);
        let result = super::block_on_blocking(p2premote_punch::api::start_udp_tunnel_with_signal(
            request,
            budget,
            call.clone(),
        ))?;
        let signal_failure = result.as_ref().err().and_then(|_| call.failure());
        entry.session.close_call(&id);
        match result {
            Ok(tunnel) => serde_json::to_string(&tunnel).map_err(|_| "signal_encode_failed".into()),
            Err(error) => Ok(json!({
                "ok": false,
                "signal_failed": signal_failure.is_some(),
                "error": signal_failure.unwrap_or(error)
            })
            .to_string()),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_top_p2premote_android_PunchNative_nativeCancelSignalCall(
    mut env: JNIEnv,
    _: JClass,
    handle: JString,
    call: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let (Ok(entry), Ok(id)) = (
            super::read_jstring(&mut env, &handle).and_then(|h| find(&h)),
            super::read_jstring(&mut env, &call),
        ) {
            entry.session.close_call(&id);
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_top_p2premote_android_PunchNative_nativeCloseSignalSession(
    mut env: JNIEnv,
    _: JClass,
    handle: JString,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Ok(handle) = super::read_jstring(&mut env, &handle) {
            let entry = sessions().lock().unwrap().remove(&handle);
            if let Some(entry) = entry {
                entry.session.close();
                entry.sender.cancel();
            }
        }
    }));
    if result.is_ok() {
        1
    } else {
        0
    }
}
