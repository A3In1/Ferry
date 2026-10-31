fn main() {
    println!("cargo:rerun-if-changed=proto/ferry.proto");
    // protox compiles .proto in pure Rust, so no system protoc is needed.
    let fds = protox::compile(["proto/ferry.proto"], ["proto"]).expect("compile ferry.proto");
    prost_build::Config::new()
        .compile_fds(fds)
        .expect("generate ferry.proto");
}
