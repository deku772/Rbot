// IShellService.aidl — Shizuku UserService interface
// Runs in a root process (UID 0) via Shizuku/Sui.
// Executes shell commands with sh -c (no su needed — already root).

package app.rbot;

interface IShellService {
    // Execute a shell command and return JSON result:
    // {"stdout":"...","stderr":"...","exitCode":0,"success":true}
    String exec(String command, int timeoutSec);

    // Check if the service is alive and running as root
    String ping();
}
