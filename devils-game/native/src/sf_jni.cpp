/*
 * sf_jni.cpp — JNI shim for Stockfish chess engine
 *
 * Bridges Java (JNI) and Stockfish's UCI loop by redirecting stdin/stdout
 * to in-memory pipes. Compiled together with all Stockfish source files
 * into a single shared library.
 *
 * Build: see CMakeLists.txt
 */

#include <jni.h>

#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <string>
#include <thread>
#include <atomic>
#include <mutex>
#include <condition_variable>
#include <queue>

// Stockfish headers
#include "uci.h"

// ─── Platform-specific pipe / dup2 ──────────────────────────────────────────

#ifdef _WIN32
  #include <io.h>
  #include <fcntl.h>
  #include <direct.h>
  #define pipe(fds) _pipe(fds, 65536, _O_BINARY)
  #define dup2     _dup2
  #define fileno   _fileno
  #define read     _read
  #define write    _write
  #define close    _close
  #define chdir    _chdir
  typedef int pipe_fd_t;
#else
  #include <unistd.h>
  typedef int pipe_fd_t;
#endif

// ─── Global state ───────────────────────────────────────────────────────────

static std::atomic<bool> g_initialized{false};
static std::atomic<bool> g_running{false};
static std::thread       g_engineThread;

// Pipe for stdin  (Java writes → engine reads)
static pipe_fd_t g_stdinPipe[2]  = {-1, -1};
// Pipe for stdout (engine writes → Java reads)
static pipe_fd_t g_stdoutPipe[2] = {-1, -1};

// Saved original file descriptors
static pipe_fd_t g_origStdin  = -1;
static pipe_fd_t g_origStdout = -1;

static std::mutex              g_outputMutex;
static std::condition_variable g_outputCV;
static std::queue<std::string> g_outputLines;
static std::atomic<bool>       g_outputEOF{false};

static std::thread g_outputReader;

// ─── Output reader thread ───────────────────────────────────────────────────
// Reads from the stdout pipe read-end, splits into lines, pushes to queue.

static void outputReaderLoop() {
    char buf[4096];
    std::string accumulator;

    while (g_running.load()) {
        int n = read(g_stdoutPipe[0], buf, sizeof(buf) - 1);
        if (n <= 0) {
            // EOF or error — flush remaining
            if (!accumulator.empty()) {
                std::lock_guard<std::mutex> lock(g_outputMutex);
                g_outputLines.push(accumulator);
                g_outputCV.notify_all();
            }
            g_outputEOF.store(true);
            g_outputCV.notify_all();
            break;
        }
        buf[n] = '\0';
        accumulator.append(buf, static_cast<size_t>(n));

        // Split on newlines
        size_t pos;
        while ((pos = accumulator.find('\n')) != std::string::npos) {
            std::string line = accumulator.substr(0, pos);
            if (!line.empty() && line.back() == '\r') line.pop_back();
            accumulator.erase(0, pos + 1);

            std::lock_guard<std::mutex> lock(g_outputMutex);
            g_outputLines.push(std::move(line));
            g_outputCV.notify_all();
        }
    }
}

// ─── Stockfish engine thread ────────────────────────────────────────────────

static void engineThreadFunc() {
    // Replicate Stockfish main():
    //   UCIEngine uci(argc, argv);
    //   uci.loop();
    char progName[] = "stockfish";
    char* argv[] = {progName, nullptr};
    int argc = 1;

    try {
        Stockfish::UCIEngine uci(argc, argv);
        uci.loop();
    } catch (...) {
        // Prevent crash propagating to JVM
    }

    g_running.store(false);
    g_outputEOF.store(true);

    // Close write-end of stdout pipe so outputReader sees EOF
    if (g_stdoutPipe[1] >= 0) {
        close(g_stdoutPipe[1]);
        g_stdoutPipe[1] = -1;
    }
    g_outputCV.notify_all();
}

// ─── JNI functions ──────────────────────────────────────────────────────────

extern "C" {

/*
 * Class:     com_devils_addon_games_chess_engine_StockfishBridge
 * Method:    init
 * Signature: (Ljava/lang/String;)V
 */
JNIEXPORT void JNICALL
Java_com_devils_addon_games_chess_engine_StockfishBridge_init(
    JNIEnv* env, jclass /*clazz*/, jstring workDir)
{
    if (g_initialized.load()) {
        jclass cls = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(cls, "Stockfish engine is already initialized");
        return;
    }

    // Create pipes
    if (pipe(g_stdinPipe) != 0 || pipe(g_stdoutPipe) != 0) {
        jclass cls = env->FindClass("java/io/IOException");
        env->ThrowNew(cls, "Failed to create pipes for engine I/O");
        return;
    }

    // Set working directory if provided (for NNUE files)
    if (workDir != nullptr) {
        const char* dir = env->GetStringUTFChars(workDir, nullptr);
        chdir(dir);
        env->ReleaseStringUTFChars(workDir, dir);
    }

    // Save originals
    g_origStdin  = dup(fileno(stdin));
    g_origStdout = dup(fileno(stdout));

    // Redirect stdin → read from g_stdinPipe[0]
    dup2(g_stdinPipe[0], fileno(stdin));
    // Redirect stdout → write to g_stdoutPipe[1]
    dup2(g_stdoutPipe[1], fileno(stdout));

    g_initialized.store(true);
    g_running.store(true);
    g_outputEOF.store(false);

    // Start threads
    g_outputReader = std::thread(outputReaderLoop);
    g_engineThread = std::thread(engineThreadFunc);
}

/*
 * Class:     com_devils_addon_games_chess_engine_StockfishBridge
 * Method:    sendCommand
 * Signature: (Ljava/lang/String;)V
 */
JNIEXPORT void JNICALL
Java_com_devils_addon_games_chess_engine_StockfishBridge_sendCommand(
    JNIEnv* env, jclass /*clazz*/, jstring command)
{
    if (!g_running.load()) {
        jclass cls = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(cls, "Engine is not running");
        return;
    }

    const char* cmd = env->GetStringUTFChars(command, nullptr);
    std::string cmdStr(cmd);
    env->ReleaseStringUTFChars(command, cmd);

    if (cmdStr.empty() || cmdStr.back() != '\n') {
        cmdStr += '\n';
    }

    write(g_stdinPipe[1], cmdStr.c_str(), static_cast<int>(cmdStr.size()));
}

/*
 * Class:     com_devils_addon_games_chess_engine_StockfishBridge
 * Method:    readLine
 * Signature: ()Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL
Java_com_devils_addon_games_chess_engine_StockfishBridge_readLine(
    JNIEnv* env, jclass /*clazz*/)
{
    std::unique_lock<std::mutex> lock(g_outputMutex);
    g_outputCV.wait(lock, [] {
        return !g_outputLines.empty() || g_outputEOF.load();
    });

    if (g_outputLines.empty()) {
        return nullptr;  // EOF
    }

    std::string line = std::move(g_outputLines.front());
    g_outputLines.pop();

    return env->NewStringUTF(line.c_str());
}

/*
 * Class:     com_devils_addon_games_chess_engine_StockfishBridge
 * Method:    isRunning
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL
Java_com_devils_addon_games_chess_engine_StockfishBridge_isRunning(
    JNIEnv* /*env*/, jclass /*clazz*/)
{
    return g_running.load() ? JNI_TRUE : JNI_FALSE;
}

/*
 * Class:     com_devils_addon_games_chess_engine_StockfishBridge
 * Method:    shutdown
 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_com_devils_addon_games_chess_engine_StockfishBridge_shutdown(
    JNIEnv* /*env*/, jclass /*clazz*/)
{
    if (!g_initialized.exchange(false)) return;

    // Send quit
    const char* quit = "quit\n";
    if (g_stdinPipe[1] >= 0) {
        write(g_stdinPipe[1], quit, static_cast<int>(strlen(quit)));
    }

    g_running.store(false);

    // Join threads
    if (g_engineThread.joinable()) g_engineThread.join();

    g_outputCV.notify_all();
    if (g_outputReader.joinable()) g_outputReader.join();

    // Close pipes
    for (auto* fd : {&g_stdinPipe[0], &g_stdinPipe[1], &g_stdoutPipe[0], &g_stdoutPipe[1]}) {
        if (*fd >= 0) { close(*fd); *fd = -1; }
    }

    // Restore stdin/stdout
    if (g_origStdin >= 0)  { dup2(g_origStdin,  fileno(stdin));  close(g_origStdin);  g_origStdin  = -1; }
    if (g_origStdout >= 0) { dup2(g_origStdout, fileno(stdout)); close(g_origStdout); g_origStdout = -1; }

    // Drain output queue
    {
        std::lock_guard<std::mutex> lock(g_outputMutex);
        while (!g_outputLines.empty()) g_outputLines.pop();
    }
    g_outputEOF.store(false);
}

} // extern "C"
