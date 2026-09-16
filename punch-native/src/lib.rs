//! Android JNI binding for the Rust port of gonc (p2premote-punch).
//!
//! Replaces the gomobile-generated `wgvpnmobile` binding on the punching /
//! signaling layer. The WireGuard data plane stays in `libwgmobile` (Go).
//!
//! Java surface: `top.p2premote.android.PunchNative`
//!   - nativeSetProtectCallback(ProtectCallback)  → VpnService.protect(fd)
//!   - nativeExchange(token, sendData, roleHint, exmode, timeoutSecs)
//!   - nativeStartUdpTunnel(requestJson)
//!   - nativeStopUdpTunnel(handleId)
//!
//! `nativeExchange` appends the "-kx" suffix to the token before use, exactly
//! like the Go mobile binding did (the Rust core expects the caller to pass
//! the already-suffixed session uid).

use std::sync::Mutex;
use std::time::Duration;

// Temporary diagnostic: mirror JNI call results into logcat (tag
// "PunchNative") until the empty-error exchange failure is understood.
#[cfg(target_os = "android")]
fn logcat(message: &str) {
    use std::ffi::CString;
    use std::os::raw::{c_char, c_int};
    extern "C" {
        fn __android_log_print(prio: c_int, tag: *const c_char, msg: *const c_char) -> c_int;
    }
    let tag = b"PunchNative\0" as *const u8 as *const c_char;
    for chunk in message.as_bytes().chunks(3500) {
        let text = CString::new(chunk.to_vec()).unwrap_or_default();
        unsafe {
            __android_log_print(4 /* INFO */, tag, text.as_ptr());
        }
    }
}

#[cfg(not(target_os = "android"))]
fn logcat(_message: &str) {}

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jstring};
use jni::{AttachGuard, JNIEnv, JavaVM};

struct ProtectState {
    vm: JavaVM,
    callback: GlobalRef,
}

// Set from the JVM thread at registration time, read from tokio worker
// threads whenever a punching socket is created.
static PROTECT_STATE: Mutex<Option<ProtectState>> = Mutex::new(None);

/// Bridge into the punch core: protect(fd) via the cached Java callback.
/// Returns false when no callback is registered or the JVM call fails;
/// the core treats a failed protect as best-effort (Go parity).
fn protect_via_jni(vm: &JavaVM, callback: &GlobalRef, fd: i32) -> bool {
    let mut env: AttachGuard<'_> = match vm.attach_current_thread() {
        Ok(env) => env,
        Err(_) => return false,
    };
    let result = env.call_method(
        callback.as_obj(),
        "protect",
        "(I)Z",
        &[JValue::Int(fd)],
    );
    match result {
        Ok(jni::objects::JValueOwned::Bool(ok)) => ok != 0,
        _ => false,
    }
}

/// Blocking block_on over a PROCESS-WIDE runtime.
///
/// The tunnel API spawns long-lived forwarder tasks; if each JNI call used
/// its own runtime, dropping it after block_on would cancel those tasks the
/// moment StartUdpTunnel returned — the punched connection would look fine
/// but carry zero traffic (Go's goroutines survive the return; a per-call
/// runtime does not).
static RUNTIME: std::sync::OnceLock<tokio::runtime::Runtime> = std::sync::OnceLock::new();

fn block_on_blocking<F: std::future::Future>(future: F) -> Result<F::Output, String> {
    let runtime = RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .expect("create tokio runtime")
    });
    Ok(runtime.block_on(future))
}

fn throw_and_null(mut env: JNIEnv, message: String) -> jstring {
    let _ = env.throw_new("java/lang/RuntimeException", message);
    std::ptr::null_mut()
}

fn read_jstring(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    env.get_string(value)
        .map(|s| s.into())
        .map_err(|err| format!("read string failed: {err}"))
}

fn err_json(message: String) -> String {
    format!(r#"{{"ok":false,"error":{}}}"#, serde_json::Value::String(message))
}

#[no_mangle]
pub extern "system" fn Java_top_p2premote_android_PunchNative_nativeSetProtectCallback(
    mut env: JNIEnv,
    _class: JClass,
    callback: JObject,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if callback.is_null() {
            *PROTECT_STATE.lock().unwrap() = None;
            p2premote_punch::easyp2p::socketprotect::set_socket_protect(None);
            return Ok::<(), String>(());
        }
        // Validate the callback method early so registration failures surface
        // at set time instead of on the first socket creation.
        let class = env.get_object_class(&callback).map_err(jni_err)?;
        let method_id = env
            .get_method_id(class, "protect", "(I)Z")
            .map_err(jni_err)?;
        let _ = method_id;
        let global = env.new_global_ref(&callback).map_err(jni_err)?;
        let vm = env.get_java_vm().map_err(jni_err)?;
        *PROTECT_STATE.lock().unwrap() = Some(ProtectState { vm, callback: global });
        p2premote_punch::easyp2p::socketprotect::set_socket_protect(Some(Box::new(|fd| {
            let guard = PROTECT_STATE.lock().unwrap();
            match guard.as_ref() {
                Some(state) => protect_via_jni(&state.vm, &state.callback, fd),
                None => false,
            }
        })));
        Ok(())
    }));
    match result {
        Ok(Ok(())) => {}
        Ok(Err(message)) => {
            let _ = env.throw_new("java/lang/RuntimeException", message);
        }
        Err(_) => {
            let _ = env.throw_new("java/lang/RuntimeException", "internal error: punch library panicked");
        }
    }
}

fn jni_err(err: jni::errors::Error) -> String {
    format!("JNI error: {err}")
}

#[no_mangle]
pub extern "system" fn Java_top_p2premote_android_PunchNative_nativeExchange(
    mut env: JNIEnv,
    _class: JClass,
    token: JString,
    send_data: JString,
    role_hint: JString,
    exmode: jint,
    timeout_secs: jint,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let token: String = read_jstring(&mut env, &token)?;
        let send_data: String = read_jstring(&mut env, &send_data)?;
        let role_hint: String = read_jstring(&mut env, &role_hint)?;
        if token.is_empty() {
            return Err("token is required".to_string());
        }
        // Go mobile binding parity: the key-exchange session uid is the punch
        // token suffixed with "-kx" so active/passive land on the same topic.
        let request = p2premote_punch::types::ExchangeInput {
            token: format!("{token}-kx"),
            exmode,
            send_data,
            role_hint,
            timeout_secs,
        };
        let timeout = if timeout_secs <= 0 { 60 } else { timeout_secs };
        let ret = block_on_blocking(p2premote_punch::api::exchange(
            request,
            Duration::from_secs(timeout as u64),
        ));
        logcat(&format!(
            "exchange done: token_len={} exmode={} timeout={} -> {}",
            token.len(),
            exmode,
            timeout,
            match &ret {
                Ok(Ok(r)) => format!("ok={} error={:?} recv_len={}", r.ok, r.error, r.recv_data.len()),
                Ok(Err(e)) => format!("core error: {:?}", e),
                Err(e) => format!("runtime error: {:?}", e),
            }
        ));
        ret
    }));
    // Unwrap carefully: catch_unwind -> block_on_blocking -> api::exchange,
    // i.e. Ok(Ok(Ok(result))) on success. Serializing the wrong level would
    // emit serde's externally-tagged {"Ok":{...}} wrapper, which the Java
    // side cannot parse (it saw ok=false with an empty error).
    let output = match result {
        Ok(Ok(Ok(result))) => serde_json::to_string(&result).unwrap_or_else(|_| {
            r#"{"ok":false,"error":"failed to encode exchange result"}"#.to_string()
        }),
        Ok(Ok(Err(message))) => err_json(message),
        Ok(Err(message)) => err_json(message),
        Err(_) => err_json("internal error: punch library panicked".to_string()),
    };
    logcat(&format!("exchange output json: {}", output));
    match env.new_string(output) {
        Ok(value) => value.into_raw(),
        Err(err) => throw_and_null(env, format!("create result string failed: {err}")),
    }
}

#[no_mangle]
pub extern "system" fn Java_top_p2premote_android_PunchNative_nativeStartUdpTunnel(
    mut env: JNIEnv,
    _class: JClass,
    request_json: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let request_json: String = read_jstring(&mut env, &request_json)?;
        let request: p2premote_punch::types::UdpTunnelInput = serde_json::from_str(&request_json)
            .map_err(|err| format!("invalid request json: {err}"))?;
        let timeout = if request.timeout_secs <= 0 { 45 } else { request.timeout_secs };
        block_on_blocking(p2premote_punch::api::start_udp_tunnel(
            request,
            Duration::from_secs(timeout as u64 + 10),
        ))
    }));
    // Same triple nesting as nativeExchange: catch_unwind -> block_on_blocking
    // -> api::start_udp_tunnel. Serializing the wrong level would emit serde's
    // {"Ok":{...}} wrapper instead of the flat JSON contract Java parses.
    let output = match result {
        Ok(Ok(Ok(result))) => serde_json::to_string(&result).unwrap_or_else(|_| {
            r#"{"ok":false,"error":"failed to encode tunnel result"}"#.to_string()
        }),
        // The core returns Err(String) for tunnel failures; surface it as the
        // JSON error contract the Java side already parses.
        Ok(Ok(Err(message))) => err_json(message),
        Ok(Err(message)) => err_json(message),
        Err(_) => err_json("internal error: punch library panicked".to_string()),
    };
    logcat(&format!("tunnel output json: {}", output));
    match env.new_string(output) {
        Ok(value) => value.into_raw(),
        Err(err) => throw_and_null(env, format!("create result string failed: {err}")),
    }
}

#[no_mangle]
pub extern "system" fn Java_top_p2premote_android_PunchNative_nativeStopUdpTunnel(
    mut env: JNIEnv,
    _class: JClass,
    handle_id: JString,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let handle_id: String = read_jstring(&mut env, &handle_id)?;
        if handle_id.is_empty() {
            return Ok::<bool, String>(false);
        }
        p2premote_punch::api::stop_udp_tunnel(&handle_id);
        Ok(true)
    }));
    match result {
        Ok(Ok(value)) => value as jboolean,
        Ok(Err(message)) => {
            let _ = env.throw_new("java/lang/RuntimeException", message);
            0
        }
        Err(_) => {
            let _ = env.throw_new("java/lang/RuntimeException", "internal error: punch library panicked");
            0
        }
    }
}
