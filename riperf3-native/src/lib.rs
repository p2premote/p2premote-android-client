use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde_json::{json, Value};
use std::net::SocketAddr;
use std::time::Duration;

const MAX_DURATION_SECS: u32 = 30;
const EXTRA_TIMEOUT_SECS: u64 = 15;

fn bps_to_mbps(value: Option<f64>) -> f64 {
    value.unwrap_or(0.0) / 1_000_000.0
}

fn server_upload_bps(server_output: Option<&Value>) -> Option<f64> {
    server_output
        .and_then(|value| value.get("end"))
        .and_then(|value| value.get("sum_received"))
        .and_then(|value| value.get("bits_per_second"))
        .and_then(Value::as_f64)
}

async fn run_client(host: &str, port: u16, duration_secs: u32, reverse: bool) -> Result<String, String> {
    let peer: SocketAddr = format!("{host}:{port}")
        .parse()
        .map_err(|err| format!("invalid peer address: {err}"))?;
    let client = riperf3::ClientBuilder::new(&peer.ip().to_string())
        .port(Some(peer.port()))
        .protocol(riperf3::TransportProtocol::Tcp)
        .reverse(reverse)
        .duration(duration_secs)
        .json_output(true)
        .get_server_output(true)
        .build()
        .map_err(|err| format!("riperf3 client build failed: {err}"))?;
    let report = tokio::time::timeout(
        Duration::from_secs(duration_secs as u64 + EXTRA_TIMEOUT_SECS),
        client.run(),
    )
    .await
    .map_err(|_| "riperf3 client timed out".to_string())?
    .map_err(|err| format!("riperf3 client failed: {err}"))?;

    let end = &report.end;
    // 与桌面端新测速逻辑一致：上传、下载各自运行一次单向测试。下载(reverse)
    // 使用本机接收值；上传优先使用被动端回传的实际接收值。
    let mbps = if reverse {
        bps_to_mbps(end.sum_received.as_ref().map(|stats| stats.bits_per_second))
    } else {
        bps_to_mbps(server_upload_bps(report.server_output_json.as_ref())
            .or_else(|| end.sum_sent.as_ref().map(|stats| stats.bits_per_second)))
    };
    Ok(json!({
        "mbps": mbps,
    })
    .to_string())
}

fn throw_and_null(mut env: JNIEnv, message: String) -> jstring {
    let _ = env.throw_new("java/lang/RuntimeException", message);
    std::ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_top_p2premote_android_Riperf3Native_nativeRunClient(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    port: i32,
    duration_secs: i32,
    reverse: u8,
) -> jstring {
    if !(1..=u16::MAX as i32).contains(&port) {
        return throw_and_null(env, "invalid riperf3 port".to_string());
    }
    if !(1..=MAX_DURATION_SECS as i32).contains(&duration_secs) {
        return throw_and_null(env, "invalid riperf3 duration".to_string());
    }
    let host: String = match env.get_string(&host) {
        Ok(value) => value.into(),
        Err(err) => return throw_and_null(env, format!("read peer host failed: {err}")),
    };
    let runtime = match tokio::runtime::Builder::new_multi_thread().enable_all().build() {
        Ok(runtime) => runtime,
        Err(err) => return throw_and_null(env, format!("create runtime failed: {err}")),
    };
    let output = match runtime.block_on(run_client(&host, port as u16, duration_secs as u32, reverse != 0)) {
        Ok(value) => value,
        Err(err) => return throw_and_null(env, err),
    };
    match env.new_string(output) {
        Ok(value) => value.into_raw(),
        Err(err) => throw_and_null(env, format!("create result string failed: {err}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn converts_bits_per_second_to_mbps() {
        assert!((bps_to_mbps(Some(25_000_000.0)) - 25.0).abs() < f64::EPSILON);
        assert_eq!(bps_to_mbps(None), 0.0);
    }

    #[test]
    fn reads_upload_from_server_report() {
        let report = json!({
            "end": {"sum_received": {"bits_per_second": 12_500_000.0}}
        });
        assert_eq!(server_upload_bps(Some(&report)), Some(12_500_000.0));
    }
}
