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

use p2premote_punch::error::{AppError, ErrorCode};
use std::sync::Mutex;
mod signal;
use std::time::Duration;

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
    let result = env.call_method(callback.as_obj(), "protect", "(I)Z", &[JValue::Int(fd)]);
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

fn block_on_blocking<F: std::future::Future>(future: F) -> Result<F::Output, AppError> {
    let runtime = RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .expect("create tokio runtime")
    });
    Ok(runtime.block_on(future))
}

fn throw_and_null(mut env: JNIEnv, message: String) -> jstring {
    let _ = env.throw_new("java/lang/RuntimeException", message.to_string());
    std::ptr::null_mut()
}

fn read_jstring(env: &mut JNIEnv, value: &JString) -> Result<String, AppError> {
    env.get_string(value).map(|s| s.into()).map_err(jni_err)
}

fn err_json(error: AppError) -> String {
    serde_json::json!({"ok": false, "error_code": error.code().as_str(), "message": error.to_string()}).to_string()
}

fn tunnel_error_json(
    failure: p2premote_punch::easyp2p::udp_tunnel::StartError,
    signal: Option<p2premote_punch::easyp2p::P2pError>,
) -> String {
    let error = signal
        .as_ref()
        .map(|e| e.app_error())
        .unwrap_or(failure.error);
    let mut result = failure.tunnel_result.unwrap_or_default();
    result.ok = false;
    result.error_code = error.code().as_str().to_owned();
    result.error = error.to_string();
    let mut value = serde_json::to_value(result).expect("tunnel fields are JSON values");
    value["signal_failed"] = serde_json::Value::Bool(signal.is_some());
    value.to_string()
}

#[no_mangle]
pub extern "system" fn Java_top_p2premote_android_PunchNative_nativeSetProtectCallback(
    mut env: JNIEnv,
    _class: JClass,
    callback: JObject,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if callback.is_null() {
            block_on_blocking(p2premote_punch::easyp2p::portmap::shutdown())?;
            p2premote_punch::easyp2p::portmap::set_discovery_hook(None);
            *PROTECT_STATE.lock().unwrap() = None;
            p2premote_punch::easyp2p::socketprotect::set_socket_protect(None);
            return Ok::<(), AppError>(());
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
        *PROTECT_STATE.lock().unwrap() = Some(ProtectState {
            vm,
            callback: global,
        });
        p2premote_punch::easyp2p::socketprotect::set_socket_protect(Some(Box::new(|fd| {
            let guard = PROTECT_STATE.lock().unwrap();
            match guard.as_ref() {
                Some(state) => protect_via_jni(&state.vm, &state.callback, fd),
                None => false,
            }
        })));
        p2premote_punch::easyp2p::portmap::set_discovery_hook(Some(Box::new(|active| {
            let guard = PROTECT_STATE.lock().unwrap();
            let Some(state) = guard.as_ref() else { return false; };
            let Ok(mut env) = state.vm.attach_current_thread() else { return false; };
            matches!(env.call_method(state.callback.as_obj(), "setMulticastEnabled", "(Z)Z", &[JValue::Bool(if active { 1 } else { 0 })]), Ok(jni::objects::JValueOwned::Bool(ok)) if ok != 0)
        })));
        p2premote_punch::easyp2p::portmap::set_enabled(true);
        Ok(())
    }));
    match result {
        Ok(Ok(())) => {}
        Ok(Err(message)) => {
            let _ = env.throw_new("java/lang/RuntimeException", message.to_string());
        }
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "internal error: punch library panicked",
            );
        }
    }
}

fn jni_err(err: jni::errors::Error) -> AppError {
    AppError::with_source(ErrorCode::NativeJniFailed, "JNI operation failed", err)
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
            return Err(AppError::new(
                ErrorCode::NativeInputInvalidArgument,
                "token is required",
            ));
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
        block_on_blocking(p2premote_punch::api::exchange(
            request,
            Duration::from_secs(timeout as u64),
        ))
    }));
    // Unwrap carefully: catch_unwind -> block_on_blocking -> api::exchange,
    // i.e. Ok(Ok(Ok(result))) on success. Serializing the wrong level would
    // emit serde's externally-tagged {"Ok":{...}} wrapper, which the Java
    // side cannot parse (it saw ok=false with an empty error).
    let output = match result {
        Ok(Ok(Ok(result))) => serde_json::to_string(&result).unwrap_or_else(|_| {
            err_json(AppError::new(
                ErrorCode::NativeEncodeFailed,
                "failed to encode exchange result",
            ))
        }),
        Ok(Ok(Err(error))) => err_json(error.into()),
        Ok(Err(message)) => err_json(message),
        Err(_) => err_json(AppError::new(
            ErrorCode::RuntimeExecutionPanicked,
            "punch library panicked",
        )),
    };
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
            .map_err(|err| {
            AppError::with_source(
                ErrorCode::NativeInputInvalidArgument,
                "invalid request JSON",
                err,
            )
        })?;
        let timeout = if request.timeout_secs <= 0 {
            45
        } else {
            request.timeout_secs
        };
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
            err_json(AppError::new(
                ErrorCode::NativeEncodeFailed,
                "failed to encode tunnel result",
            ))
        }),
        // The core returns Err(String) for tunnel failures; surface it as the
        // JSON error contract the Java side already parses.
        Ok(Ok(Err(error))) => tunnel_error_json(error, None),
        Ok(Err(message)) => err_json(message),
        Err(_) => err_json(AppError::new(
            ErrorCode::RuntimeExecutionPanicked,
            "punch library panicked",
        )),
    };
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
            return Ok::<bool, AppError>(false);
        }
        p2premote_punch::api::stop_udp_tunnel(&handle_id);
        Ok(true)
    }));
    match result {
        Ok(Ok(value)) => value as jboolean,
        Ok(Err(message)) => {
            let _ = env.throw_new("java/lang/RuntimeException", message.to_string());
            0
        }
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "internal error: punch library panicked",
            );
            0
        }
    }
}

/// Application-only adapter; the gonc-compatible library API is unchanged.
#[no_mangle]
pub extern "system" fn Java_top_p2premote_android_PunchNative_nativeDetectNat(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        block_on_blocking(p2premote_punch::api::detect_nat(
            &["udp4", "udp6", "tcp4", "tcp6"],
            Duration::from_secs(6),
        ))
    }));
    let output = match result {
        Ok(Ok(Ok(evidence))) => serde_json::to_string(&evidence).unwrap_or_else(|_| "[]".into()),
        _ => "[]".into(),
    };
    match env.new_string(output) {
        Ok(value) => value.into_raw(),
        Err(error) => throw_and_null(env, format!("create NAT result failed: {error}")),
    }
}
