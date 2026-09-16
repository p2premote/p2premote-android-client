fn main() {
    // Temporary diagnostic: link liblog so the JNI layer can write the raw
    // exchange result JSON to logcat (tag PunchNative).
    println!("cargo:rustc-link-lib=log");
}
