package app.botdrop.shizuku;

import android.content.pm.PackageManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Parcel;

import com.termux.shared.logger.Logger;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import moe.shizuku.server.IShizukuService;
import moe.shizuku.server.IRemoteProcess;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

/**
 * Embedded Binder shell service entry point.
 * Executes commands and returns JSON results for the local bridge path.
 */
public class ShellService extends Service {

    private static final String LOG_TAG = "ShizukuShellService";
    private static final int DEFAULT_TIMEOUT_MS = 30000;
    private static final String FALLBACK_SHARED_ROOT = "/data/local/tmp/botdrop_tmp";
    private static final String FALLBACK_TERMUX_HOME = com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH;
    private static final int SHIZUKU_STARTUP_RETRY_COUNT = 3;
    private static final long SHIZUKU_STARTUP_RETRY_DELAY_MS = 300L;

    private final ExecutorService mStreamExecutor = Executors.newCachedThreadPool();

    private final IShellService.Stub mBinder = new IShellService.Stub() {
        @Override
        public String executeCommand(String command, int timeoutMs) {
            return ShellService.this.executeCommandInternal(command, timeoutMs);
        }

        @Override
        public void destroy() {
            Logger.logInfo(LOG_TAG, "Destroy requested by binder client");
            stopSelf();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mStreamExecutor.shutdownNow();
    }

    private String executeCommandInternal(String command, int timeoutMs) {
        String safeCommand = command == null ? "" : command;
        int effectiveTimeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;

        JSONObject result = new JSONObject();
        int exitCode = -1;
        String stdout = "";
        String stderr = "";

        Process process = null;
        try {
            process = createProcess(safeCommand);
            if (process == null) {
                stderr = "Shizuku execution unavailable";
                return buildJsonResult(exitCode, stdout, stderr);
            }

            Future<String> stdoutFuture = mStreamExecutor.submit(readProcessOutput(process.getInputStream()));
            Future<String> stderrFuture = mStreamExecutor.submit(readProcessOutput(process.getErrorStream()));

            if (!process.waitFor(effectiveTimeout, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                exitCode = -1;
                stderr = "Command timeout after " + effectiveTimeout + " ms";
            } else {
                exitCode = process.exitValue();
            }

            long streamTimeoutMs = Math.max(effectiveTimeout + 2000L, 2000L);
            stdout = getFutureResult(stdoutFuture, streamTimeoutMs);
            if (stderr == null || stderr.isEmpty()) {
                stderr = getFutureResult(stderrFuture, streamTimeoutMs);
            } else {
                String tail = getFutureResult(stderrFuture, streamTimeoutMs);
                if (!tail.isEmpty()) {
                    stderr = stderr + tail;
                }
            }
        } catch (InterruptedException e) {
            Logger.logError(LOG_TAG, "executeCommand failed: " + e.getMessage());
            stderr = (stderr == null ? "" : stderr) + "\n" + e.getMessage();
            if (process != null) {
                process.destroyForcibly();
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } catch (TimeoutException e) {
            stderr = "Failed to collect command output: " + e.getMessage();
            if (process != null) {
                process.destroyForcibly();
            }
            Logger.logWarn(LOG_TAG, "Stream read timed out");
        } catch (ExecutionException e) {
            stderr = "Failed to collect command output: " + e.getMessage();
            Logger.logWarn(LOG_TAG, stderr);
        }

        try {
            result.put("exitCode", exitCode);
            result.put("stdout", stdout == null ? "" : stdout);
            result.put("stderr", stderr == null ? "" : stderr);
            result.put("success", exitCode == 0);
        } catch (JSONException e) {
            Logger.logWarn(LOG_TAG, "Failed to build result JSON: " + e.getMessage());
        }

        return result.toString();
    }

    private String buildJsonResult(int exitCode, String stdout, String stderr) {
        JSONObject result = new JSONObject();
        try {
            result.put("exitCode", exitCode);
            result.put("stdout", stdout == null ? "" : stdout);
            result.put("stderr", stderr == null ? "" : stderr);
            result.put("success", exitCode == 0);
        } catch (JSONException e) {
            Logger.logWarn(LOG_TAG, "Failed to build result JSON: " + e.getMessage());
        }
        return result.toString();
    }

    private Process createProcess(String command) {
        Process shizukuProcess = createShizukuProcessWithRetry(command);
        if (shizukuProcess != null) {
            return shizukuProcess;
        }
        return null;
    }

    private Process createShizukuProcess(String command) {
        if (!Shizuku.pingBinder()) {
            return null;
        }

        int permission;
        try {
            permission = Shizuku.checkSelfPermission();
        } catch (Throwable e) {
            Logger.logWarn(LOG_TAG, "Shizuku checkSelfPermission failed: " + e.getMessage());
            return null;
        }

        if (permission != PackageManager.PERMISSION_GRANTED) {
            Logger.logWarn(LOG_TAG, "Shizuku permission not granted");
            return null;
        }

        try {
            IShizukuService service = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            if (service == null) {
                return null;
            }
            IRemoteProcess remoteProcess = service.newProcess(selectCommand(command), getShizukuEnv(), null);
            return createWrappedShizukuRemoteProcess(remoteProcess);
        } catch (SecurityException e) {
            Logger.logWarn(LOG_TAG, "Shizuku security denied: " + e.getMessage());
            return null;
        } catch (Throwable e) {
            Logger.logWarn(LOG_TAG, "Shizuku process execution failed: " + e.getMessage());
            return null;
        }
    }

    private Process createShizukuProcessWithRetry(String command) {
        for (int attempt = 0; attempt < SHIZUKU_STARTUP_RETRY_COUNT; attempt++) {
            if (!Shizuku.pingBinder()) {
                if (attempt == SHIZUKU_STARTUP_RETRY_COUNT - 1) {
                    Logger.logWarn(LOG_TAG, "Shizuku binder not ready after retry");
                    return null;
                }
                sleepNoThrow(SHIZUKU_STARTUP_RETRY_DELAY_MS * (attempt + 1));
                continue;
            }

            Process process = createShizukuProcess(command);
            if (process != null) {
                return process;
            }

            if (attempt < SHIZUKU_STARTUP_RETRY_COUNT - 1) {
                sleepNoThrow(SHIZUKU_STARTUP_RETRY_DELAY_MS * (attempt + 1));
            }
        }
        return null;
    }

    private void sleepNoThrow(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Process createWrappedShizukuRemoteProcess(IRemoteProcess remoteProcess) {
        if (remoteProcess == null) {
            return null;
        }

        try {
            Parcel parcel = Parcel.obtain();
            try {
                parcel.writeStrongBinder(remoteProcess.asBinder());
                parcel.setDataPosition(0);
                ShizukuRemoteProcess wrapped = ShizukuRemoteProcess.CREATOR.createFromParcel(parcel);
                return wrapped;
            } finally {
                parcel.recycle();
            }
        } catch (Throwable e) {
            Logger.logWarn(LOG_TAG, "Failed to wrap remote process: " + e.getMessage());
        }
        return null;
    }

    private String[] getShizukuEnv() {
        Map<String, String> env = new LinkedHashMap<>(System.getenv());

        String[] envArray = new String[env.size()];
        int i = 0;
        for (Map.Entry<String, String> entry : env.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            envArray[i++] = key + "=" + (value == null ? "" : value);
        }

        return envArray;
    }

    private String[] selectCommand(String command) {
        String payload = ensureTermuxEnvironment(command);
        return new String[]{"/system/bin/sh", "-c", payload};
    }

    private String ensureTermuxEnvironment(String command) {
        String payload = command == null ? "" : command;
        String sharedRoot = resolveSharedRoot();
        String termuxHome = resolveTermuxHome();
        if (termuxHome == null || sharedRoot == null) {
            return payload;
        }

        final String quotedRoot = quoteShellArg(sharedRoot);
        final String quotedHome = quoteShellArg(termuxHome);
        return "export BOTDROP_SHARED_ROOT=" + quotedRoot
            + "; export BOTDROP_TERMUX_HOME=" + quotedHome
            + "; export HOME=" + quotedHome
            + "; cd " + quotedRoot + "; " + payload;
    }

    private String resolveSharedRoot() {
        final String[] candidates = new String[]{
            System.getenv("BOTDROP_SHARED_ROOT"),
            "/data/local/tmp/botdrop_tmp",
            FALLBACK_SHARED_ROOT,
        };

        for (final String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String trimmed = candidate.trim();
            if (!trimmed.startsWith("/data/local/tmp/") &&
                !trimmed.equals("/data/local/tmp")) {
                continue;
            }
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return FALLBACK_SHARED_ROOT;
    }

    private String resolveTermuxHome() {
        final String[] candidates = new String[]{
            System.getenv("BOTDROP_TERMUX_HOME"),
            System.getenv("TERMUX_HOME"),
            System.getenv("HOME"),
            FALLBACK_TERMUX_HOME,
        };

        for (final String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            final String trimmed = candidate.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return FALLBACK_TERMUX_HOME;
    }

    private String quoteShellArg(String value) {
        String safe = value == null ? "" : value;
        return "'" + safe.replace("'", "'\\''") + "'";
    }

    private Callable<String> readProcessOutput(InputStream inputStream) {
        return () -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    sb.append(buffer, 0, read);
                }
                return sb.toString();
            } catch (IOException e) {
                Logger.logWarn(LOG_TAG, "Failed reading stream: " + e.getMessage());
                return "";
            }
        };
    }

    private String getFutureResult(Future<String> future, long timeoutMs)
        throws ExecutionException, InterruptedException, TimeoutException {
        return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    }
}
