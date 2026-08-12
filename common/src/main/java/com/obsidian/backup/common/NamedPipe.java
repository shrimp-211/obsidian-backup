package com.obsidian.backup.common;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Minimal JNA binding for a Windows named-pipe client.
 *
 * Java's {@code UnixDomainSocketAddress} is Unix-only, so on Windows the
 * Sidecar IPC uses a named pipe. This class wraps {@code CreateFile} /
 * {@code ReadFile} / {@code WriteFile} / {@code CloseHandle} from kernel32
 * into plain {@link InputStream} / {@link OutputStream} streams so the rest of
 * {@link IpcClient} stays transport-agnostic.
 */
public final class NamedPipe {

    private NamedPipe() {}

    /** Open a named pipe and return its read/write streams. */
    public static PipeConnection open(String logicalName) throws IOException {
        String pipeName = "\\\\.\\pipe\\" + sanitize(logicalName);
        WinNT.HANDLE handle = Kernel32.INSTANCE.CreateFile(
            pipeName,
            WinNT.GENERIC_READ | WinNT.GENERIC_WRITE,
            0,
            null,
            WinNT.OPEN_EXISTING,
            WinNT.FILE_FLAG_OVERLAPPED,
            null
        );
        if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
            throw new IOException("Cannot open named pipe " + pipeName
                + " (error " + Kernel32.INSTANCE.GetLastError() + ")");
        }
        return new PipeConnection(
            new PipeInputStream(handle),
            new PipeOutputStream(handle)
        );
    }

    /** Convert a logical address into a valid pipe name segment. */
    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '-');
        }
        return sb.toString();
    }

    public static final class PipeConnection implements AutoCloseable {
        public final InputStream input;
        public final OutputStream output;
        private boolean closed = false;

        PipeConnection(InputStream input, OutputStream output) {
            this.input = input;
            this.output = output;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                try { input.close(); } catch (IOException ignored) {}
                try { output.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static final class PipeInputStream extends InputStream {
        private final WinNT.HANDLE handle;

        PipeInputStream(WinNT.HANDLE handle) {
            this.handle = handle;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            byte[] buf = new byte[len];
            IntByReference read = new IntByReference();
            boolean ok = Kernel32.INSTANCE.ReadFile(handle, buf, len, read, null);
            if (!ok) {
                int err = Kernel32.INSTANCE.GetLastError();
                if (err == 109 /* ERROR_BROKEN_PIPE */) {
                    return -1; // EOF
                }
                throw new IOException("Named pipe read failed (error " + err + ")");
            }
            int n = read.getValue();
            if (n == 0) {
                return -1;
            }
            System.arraycopy(buf, 0, b, off, n);
            return n;
        }

        @Override
        public void close() {
            Kernel32.INSTANCE.CloseHandle(handle);
        }
    }

    private static final class PipeOutputStream extends OutputStream {
        private final WinNT.HANDLE handle;

        PipeOutputStream(WinNT.HANDLE handle) {
            this.handle = handle;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            byte[] buf = new byte[len];
            System.arraycopy(b, off, buf, 0, len);
            IntByReference written = new IntByReference();
            boolean ok = Kernel32.INSTANCE.WriteFile(handle, buf, len, written, null);
            if (!ok) {
                throw new IOException("Named pipe write failed (error "
                    + Kernel32.INSTANCE.GetLastError() + ")");
            }
        }

        @Override
        public void close() {
            Kernel32.INSTANCE.CloseHandle(handle);
        }
    }
}
