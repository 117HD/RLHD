package rs117.hd.utils;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class FileDownloader {

	public interface DownloadListener {
		void onStarted();

		void onFailure(Call call, IOException e);

		void onProgress(int progress);

		void onFinished();
	}

	private OkHttpClient client;

	public FileDownloader() {
		client = new OkHttpClient();
	}

	public void downloadFile(String url, File destination, final DownloadListener listener) {
		downloadFile(url, destination, null, listener);
	}

	public void downloadFile(String url, File destination, Long expectedFileSize, final DownloadListener listener) {
		listener.onStarted(); // Trigger onStarted callback

		Request request = new Request.Builder()
			.url(url)
			.build();

		client.newCall(request).enqueue(new Callback() {
			@Override
			public void onFailure(Call call, IOException e) {
				listener.onFailure(call, e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException {
				if (!response.isSuccessful()) {
					onFailure(call, new IOException("Unexpected code " + response));
					return;
				}

				ResponseBody body = response.body();
				if (body == null) {
					onFailure(call, new IOException("Response body is null"));
					return;
				}

				long contentLength = expectedFileSize != null ? expectedFileSize : body.contentLength();
				long bytesRead = 0;
				byte[] buffer = new byte[1024 * 4]; // 4KB buffer

				try (FileOutputStream fos = new FileOutputStream(destination)) {
					while (true) {
						int read = body.byteStream().read(buffer);
						if (read == -1) {
							break;
						}
						fos.write(buffer, 0, read);
						bytesRead += read;
						if (contentLength > 0) {
							int progress = (int) ((bytesRead * 100) / contentLength);
							listener.onProgress(progress);
						} else {
							listener.onProgress(-1);
						}
					}
					fos.flush();
					listener.onFinished();
				} catch (IOException e) {
					onFailure(call, e);
				} finally {
					body.close();
				}
			}
		});
	}
}