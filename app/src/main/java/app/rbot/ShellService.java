package app.rbot;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Shizuku UserService implementation — runs in a root process (UID 0).
 * Executes shell commands via sh -c (no su needed, already root).
 * <p>
 * Bound via Shizuku.bindUserService() when Shizuku/Sui auth mode is active.
 */
public class ShellService extends IShellService.Stub {

    private static final String TAG = "ShellService";

    @Override
    public String exec(String command, int timeoutSec) {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        try {
            Log.d(TAG, "Executing (sh -c): " + command);

            final Process process = new ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(false)
                .start();

            final long[] lastActivity = {System.currentTimeMillis()};
            final long startTime = System.currentTimeMillis();
            final long maxWallTime = timeoutSec * 3 * 1000L;
            final long idleTimeout = timeoutSec * 1000L;

            Thread stdoutThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                        lastActivity[0] = System.currentTimeMillis();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "stdout read error: " + e.getMessage());
                }
            });

            Thread stderrThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                        lastActivity[0] = System.currentTimeMillis();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "stderr read error: " + e.getMessage());
                }
            });

            stdoutThread.start();
            stderrThread.start();

            while (process.isAlive()) {
                long now = System.currentTimeMillis();
                long elapsed = now - startTime;
                long idle = now - lastActivity[0];

                if (elapsed > maxWallTime) {
                    process.destroyForcibly();
                    return jsonResult(stdout.toString(),
                        "命令超过最大运行时间 (" + (maxWallTime / 1000) + "s)", -1);
                }

                if (idle > idleTimeout) {
                    process.destroyForcibly();
                    return jsonResult(stdout.toString(),
                        "命令超时 (" + (idle / 1000) + "s 无输出)", -1);
                }

                Thread.sleep(500);
            }

            stdoutThread.join(2000);
            stderrThread.join(2000);

            int exitCode = process.exitValue();
            return jsonResult(stdout.toString(), stderr.toString(), exitCode);

        } catch (Exception e) {
            Log.e(TAG, "Command execution failed: " + e.getMessage());
            return jsonResult(stdout.toString(), e.getMessage(), -1);
        }
    }

    @Override
    public String ping() {
        int uid = android.os.Process.myUid();
        return jsonResult("pong uid=" + uid, "", 0);
    }

    /**
     * Build a JSON result string. Minimal JSON — no external dependency needed.
     */
    private String jsonResult(String stdout, String stderr, int exitCode) {
        return "{\"stdout\":" + jsonString(stdout)
            + ",\"stderr\":" + jsonString(stderr)
            + ",\"exitCode\":" + exitCode
            + ",\"success\":" + (exitCode == 0)
            + "}";
    }

    /** Escape a string for JSON — handles quotes, backslashes, newlines. */
    private String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    /**
     * Handle Shizuku's destroy signal.
     * Shizuku sends a transact with code 16777115 to request service destruction.
     * We override onTransact to catch this and exit the process.
     */
    @Override
    public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags)
            throws android.os.RemoteException {
        if (code == 16777115) {
            Log.i(TAG, "ShellService destroy signal received, exiting process");
            System.exit(0);
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }
}
