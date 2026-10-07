// tauri-build puts an empty msvcrt.lib on the link path for the static VC runtime; the matching linker
// arguments only reach the server crate's own binaries.
fn main() {
    if std::env::var("CARGO_CFG_TARGET_ENV").as_deref() != Ok("msvc") {
        return;
    }
    for lib in ["libvcruntimed", "vcruntime", "vcruntimed", "libcmtd", "msvcrt", "msvcrtd", "libucrt", "libucrtd"] {
        println!("cargo:rustc-link-arg=/NODEFAULTLIB:{lib}.lib");
    }
    for lib in ["libcmt", "libvcruntime", "ucrt"] {
        println!("cargo:rustc-link-arg=/DEFAULTLIB:{lib}.lib");
    }
}
