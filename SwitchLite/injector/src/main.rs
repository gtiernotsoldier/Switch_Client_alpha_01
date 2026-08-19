// main.rs — SwitchLite Rust-led injector entry point
//
// Sandwich Architecture — Layer 1 (injector) rewritten in Rust.
// Responsibilities (migrated from the old C++ main.cpp):
//   1. Find the Minecraft process (javaw.exe with "minecraft" window)
//   2. Detect version + platform
//   3. Extract the embedded agent.jar
//   4. Inject the C++ payload.dll (which does the in-process JNI attach)
//   5. Show diagnostic logs
//
// The C++ payload.dll stays C++ (it must run inside javaw.exe and talk JNI /
// the Windows Attach pipe); everything around it is Rust.

mod inject;
mod process;
mod resource;
mod version;

fn main() {
    println!("[*] SwitchLite Injector v0.1.0-alpha (Rust)");

    // Step 1: Find Minecraft process
    println!("[*] Scanning for Minecraft process...");
    let mc = process::find_minecraft_process();
    if !mc.valid {
        eprintln!("[x] Minecraft process not found.");
        pause_exit();
        std::process::exit(1);
    }
    println!(
        "[+] Found Minecraft (PID: {}, Window: \"{}\")",
        mc.pid, mc.window_title
    );

    // Step 2: Detect version & platform
    println!("[*] Detecting version & platform...");
    let info = version::parse_minecraft_version(&mc.path, &mc.window_title);
    if !info.valid {
        eprintln!("[x] Failed to identify Minecraft version.");
        pause_exit();
        std::process::exit(1);
    }
    println!(
        "[+] Version: {} | Platform: {}",
        info.version, info.platform
    );

    // Step 3: Extract embedded agent.jar
    println!("[*] Extracting embedded agent.jar...");
    let agent_path = resource::get_embedded_agent_path();
    if agent_path.is_empty() {
        eprintln!("[x] Embedded agent.jar not found.");
        pause_exit();
        std::process::exit(1);
    }
    println!("[+] Agent ready: {}", agent_path);

    // Step 4: Inject the Java agent via C++ payload.dll
    println!("[*] Injecting into JVM...");
    if !inject::inject_java_agent(&mc, &agent_path, &info.platform, &info.version) {
        eprintln!("[x] Failed to inject Java Agent.");
        pause_exit();
        std::process::exit(1);
    }
    println!("[+] Java Agent injected successfully.");

    println!("\n[+] Done.");
    pause_exit();
}

fn pause_exit() {
    #[cfg(windows)]
    {
        // "Press any key" — read a line so the console stays open.
        let _ = std::io::stdin().read_line(&mut String::new());
    }
}
