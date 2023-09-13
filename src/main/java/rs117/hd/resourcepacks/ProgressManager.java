package rs117.hd.resourcepacks;

import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;

public class ProgressManager extends ResponseBody {
	private final ResponseBody responseBody;
	private final ProgressListener progressListener;
	private BufferedSource bufferedSource;

	ProgressManager(ResponseBody responseBody, ProgressListener progressListener) {
		this.responseBody = responseBody;
		this.progressListener = progressListener;
	}

	@Override
	public MediaType contentType() {
		return responseBody.contentType();
	}

	@Override
	public long contentLength() {
		return responseBody.contentLength();
	}

	@Override
	public BufferedSource source() {
		if (bufferedSource == null) {
			bufferedSource = Okio.buffer(source(responseBody.source()));
		}
		return bufferedSource;
	}

	boolean firstUpdate = true;

	private Source source(Source source) {
		return new ForwardingSource(source) {
			long totalBytesRead = 0L;

			@Override
			public long read(Buffer sink, long byteCount) throws IOException {
				long bytesRead = 0;
				try {
					bytesRead = super.read(sink, byteCount);
				} catch (Exception ex) {
					sink.close();
					System.out.println("Error while reading stream: " + ex);
				}

				// read() returns the number of bytes read, or -1 if this source is exhausted.
				totalBytesRead += bytesRead != -1 ? bytesRead : 0;
				if (firstUpdate) {
					firstUpdate = false;
					progressListener.started();
				}
				if (responseBody.contentLength() > 0)
					progressListener.progress(totalBytesRead, responseBody.contentLength());

				return bytesRead;
			}
		};
	}
}

interface ProgressListener {
	void finishedDownloading();

	void progress(long bytesRead, long contentLength);

	void started();
}
