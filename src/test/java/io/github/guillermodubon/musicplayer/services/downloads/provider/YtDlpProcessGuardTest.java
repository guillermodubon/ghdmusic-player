package io.github.guillermodubon.musicplayer.services.downloads.provider;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YtDlpProcessGuardTest {

    @Test
    void cancellationTerminatesTheOwnedProcess() throws Exception {
        FakeProcess process = new FakeProcess();
        AtomicBoolean cancelled = new AtomicBoolean();
        try (YtDlpProcessGuard ignored = YtDlpProcessGuard.watch(process, cancelled::get)) {
            cancelled.set(true);
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (process.isAlive() && System.nanoTime() < deadline) Thread.sleep(10L);
            assertFalse(process.isAlive());
        }
    }

    private static final class FakeProcess extends Process {
        private final AtomicBoolean alive = new AtomicBoolean(true);

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) { return !alive.get(); }
        @Override public int exitValue() { return alive.get() ? 0 : 1; }
        @Override public void destroy() { alive.set(false); }
        @Override public Process destroyForcibly() { alive.set(false); return this; }
        @Override public boolean isAlive() { return alive.get(); }
    }
}
