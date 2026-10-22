"""Session logging for AgentLoop — mirrors the Kotlin writeLog / initSessionLog / finalizeSessionLog."""

import inspect
import os
import time
from datetime import datetime
from utils import timestamp_str


class SessionLogger:
    # Stay in the app config dir so a rename does not leave logs at the old path.
    LOG_DIR = "/sdcard/.xomniclaw/workspace/logs"
    CONTEXT_FILE = "/sdcard/.xomniclaw/workspace/.xomniclaw/artifact-context.txt"

    def __init__(self):
        self._log_file = None
        self._buffer = []

    def init_session(self, user_message):
        try:
            os.makedirs(self.LOG_DIR, exist_ok=True)
            ts, prefix = self._load_artifact_context(user_message)
            filename = f"agentloop_{ts}_{prefix}.log"
            self._log_file = os.path.join(self.LOG_DIR, filename)
            self._buffer = []
            header = (
                f"========== Agent Loop Session ==========\n"
                f"Start time: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n"
                f"User message: {user_message}\n"
                f"========================================\n\n"
            )
            with open(self._log_file, "w", encoding="utf-8") as f:
                f.write(header)
        except Exception:
            self._log_file = None

    def _load_artifact_context(self, user_message):
        default_ts = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
        default_prefix = "".join(
            c if c.isalnum() or "\u4e00" <= c <= "\u9fa5" else "_"
            for c in user_message[:20]
        ).strip("_") or "no_query"

        try:
            if not os.path.exists(self.CONTEXT_FILE):
                return default_ts, default_prefix

            with open(self.CONTEXT_FILE, "r", encoding="utf-8") as f:
                lines = [line.strip() for line in f.readlines()]

            prefix = lines[0] if len(lines) > 0 and lines[0] else default_prefix
            ts = lines[2] if len(lines) > 2 and lines[2] else default_ts
            return ts, prefix
        except Exception:
            return default_ts, default_prefix

    def write(self, message):
        # Log enhancement: record the caller file and line to locate the source precisely.
        caller = self._resolve_caller_frame()
        source_tag = self._format_source_tag(caller)
        line = f"[{timestamp_str()}] {source_tag} {message}"
        self._buffer.append(line)
        if self._log_file:
            try:
                with open(self._log_file, "a", encoding="utf-8") as f:
                    f.write(line + "\n")
            except Exception:
                pass

    def _resolve_caller_frame(self):
        """
        Find the real business call frame (skipping SessionLogger internals).
        """
        frame = inspect.currentframe()
        try:
            cur = frame.f_back
            self_file = os.path.abspath(__file__)
            while cur is not None:
                code = cur.f_code
                filename = os.path.abspath(code.co_filename)
                if filename != self_file:
                    return filename, cur.f_lineno
                cur = cur.f_back
            return None
        finally:
            # Avoid frame reference cycles delaying GC.
            del frame

    def _format_source_tag(self, caller_info):
        """
        Format the caller as [path:line].
        """
        if not caller_info:
            return "[unknown:0]"
        filename, lineno = caller_info
        # Normalize to forward slashes for cross-platform reading and search.
        normalized = filename.replace("\\", "/")
        return f"[{normalized}:{lineno}]"

    def finalize(self, iterations, tools_used, final_content_length):
        if not self._log_file:
            return
        try:
            with open(self._log_file, "a", encoding="utf-8") as f:
                f.write(f"\n========================================\n")
                f.write(f"End time: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
                f.write(f"Total iterations: {iterations}\n")
                f.write(f"Tools used: {', '.join(tools_used)}\n")
                f.write(f"Final content length: {final_content_length} chars\n")
                f.write(f"========================================\n")
        except Exception:
            pass

    def get_buffer(self):
        return list(self._buffer)
