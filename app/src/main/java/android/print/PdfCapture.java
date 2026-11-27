package android.print;

import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

import java.io.File;

/**
 * Drives a {@link PrintDocumentAdapter} (e.g. from a WebView) straight into a PDF file.
 * Lives in android.print because the result-callback constructors are package-private.
 */
public final class PdfCapture {

    public interface Callback {
        void onDone(boolean success);
    }

    private PdfCapture() {}

    public static void write(PrintDocumentAdapter adapter, PrintAttributes attrs, File out, Callback cb) {
        adapter.onStart();
        adapter.onLayout(null, attrs, new CancellationSignal(), new PrintDocumentAdapter.LayoutResultCallback() {
            @Override
            public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                final ParcelFileDescriptor pfd;
                try {
                    pfd = ParcelFileDescriptor.open(out,
                        ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_READ_WRITE);
                } catch (Exception e) {
                    finish(adapter, cb, false);
                    return;
                }
                adapter.onWrite(new PageRange[] {PageRange.ALL_PAGES}, pfd, new CancellationSignal(),
                    new PrintDocumentAdapter.WriteResultCallback() {
                        @Override
                        public void onWriteFinished(PageRange[] pages) {
                            close(pfd);
                            finish(adapter, cb, true);
                        }

                        @Override
                        public void onWriteFailed(CharSequence error) {
                            close(pfd);
                            finish(adapter, cb, false);
                        }

                        @Override
                        public void onWriteCancelled() {
                            close(pfd);
                            finish(adapter, cb, false);
                        }
                    });
            }

            @Override
            public void onLayoutFailed(CharSequence error) {
                finish(adapter, cb, false);
            }

            @Override
            public void onLayoutCancelled() {
                finish(adapter, cb, false);
            }
        }, null);
    }

    private static void close(ParcelFileDescriptor pfd) {
        try {
            pfd.close();
        } catch (Exception ignored) {
        }
    }

    private static void finish(PrintDocumentAdapter adapter, Callback cb, boolean ok) {
        try {
            adapter.onFinish();
        } catch (Exception ignored) {
        }
        cb.onDone(ok);
    }
}
