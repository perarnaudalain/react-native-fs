package com.rnfs;

import com.facebook.react.bridge.ReadableMapKeySetIterator;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.ForwardingSink;
import okio.Okio;
import okio.Sink;

public class Downloader {

  private final OkHttpClient client = new OkHttpClient();
  DownloadResult res;
  private Call request;

  void execute(final DownloadParams params) {
    res = new DownloadResult();
    final Request.Builder request = new Request.Builder().url(params.src);
    final ReadableMapKeySetIterator headerIterator = params.headers.keySetIterator();
    while (headerIterator.hasNextKey()) {
      String headerKey = headerIterator.nextKey();
      request.addHeader(
        headerKey,
        params.headers.getString(params.headers.getString(headerKey)));
    }

    OkHttpClient requestClient = client.newBuilder()
      .connectTimeout(params.connectionTimeout, TimeUnit.MILLISECONDS)
      .readTimeout(params.readTimeout, TimeUnit.MILLISECONDS)
      .build();

    this.request = requestClient.newCall(request.build());
    this.request.enqueue(new Callback() {
      @Override
      public void onFailure(Call call, IOException e) {
        res.exception = e;
        params.onTaskCompleted.onTaskCompleted(res);
      }

      @Override
      public void onResponse(Call call, Response response) throws IOException {
        try (ResponseBody responseBody = response.body()) {
          if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

          if (params.onDownloadBegin != null) {
            Headers responseHeaders = response.headers();
            Map<String, String> flatHeaders = new HashMap<>(responseHeaders.size());
            for (Map.Entry<String, List<String>> entry : responseHeaders.toMultimap().entrySet()) {
              String headerKey = entry.getKey();
              String headerValue = entry.getValue().get(0);
              if (headerKey != null && headerValue != null) {
                flatHeaders.put(entry.getKey(), entry.getValue().get(0));
              }
            }
            params.onDownloadBegin.onDownloadBegin(response.code(), responseBody.contentLength(), flatHeaders);
          }

          try (Sink dest = wrapWithReporter(Okio.sink(params.dest), responseBody.contentLength(), params)) {
            responseBody.source().readAll(dest);
            res.bytesWritten = params.dest.length();
            res.statusCode = response.code();
            params.onTaskCompleted.onTaskCompleted(res);
          }
        }
      }
    });
  }

  protected void stop() {
    if (this.request != null) {
      this.request.cancel();
    }
  }

  private Sink wrapWithReporter(final Sink source, long total, final DownloadParams params) {
    if (params.onDownloadProgress == null) {
      return new NoopCountingSink(source);
    } else if (params.progressInterval > 0) {
      return new ScheduledCountingSink(source, total, params.progressInterval, params.onDownloadProgress);
    } else if (params.progressDivider > 0) {
      return new PercentCountingSink(source, total, params.progressDivider, params.onDownloadProgress);
    } else {
      return new SimpleCountingSink(source, total, params.onDownloadProgress);
    }
  }

  private final class NoopCountingSink extends ForwardingSink {

    public NoopCountingSink(Sink delegate) {
      super(delegate);
    }
  }

  private final class SimpleCountingSink extends ForwardingSink {
    @Nonnull
    private final DownloadParams.OnDownloadProgress progressCallback;
    private long bytesWritten = 0;
    private final long total;


    public SimpleCountingSink(Sink delegate,
                               long total,
                               @Nonnull DownloadParams.OnDownloadProgress progressCallback) {
      super(delegate);
      this.total = total;
      this.progressCallback = progressCallback;
    }


    @Override
    public void write(Buffer source, long byteCount) throws IOException {
      super.write(source, byteCount);

      bytesWritten += byteCount;
      progressCallback.onDownloadProgress(total, bytesWritten);
    }

  }

  private final class PercentCountingSink extends ForwardingSink {

    @Nonnull
    private final DownloadParams.OnDownloadProgress progressCallback;
    private long bytesWritten = 0;
    private final long total;
    private final float stepSize;
    private float nextStep;

    public PercentCountingSink(Sink delegate,
                               long total,
                               float stepSize,
                               @Nonnull DownloadParams.OnDownloadProgress progressCallback) {
      super(delegate);
      this.total = total;
      this.stepSize = stepSize;
      this.nextStep = stepSize;
      this.progressCallback = progressCallback;
    }

    @Override
    public void write(Buffer source, long byteCount) throws IOException {
      super.write(source, byteCount);

      bytesWritten += byteCount;
      if (nextStep <= ((float) bytesWritten) / total * 100) {
        nextStep += Math.min(100f, stepSize);
        progressCallback.onDownloadProgress(total, bytesWritten);
      }
    }
  }

  private final class ScheduledCountingSink extends ForwardingSink {

    @Nonnull
    private final DownloadParams.OnDownloadProgress progressCallback;
    private long bytesWritten = 0;
    private final long total;
    private float lastUpdateTime = System.currentTimeMillis();
    private final float progressInterval;

    public ScheduledCountingSink(Sink delegate,
                                 long total,
                                 float progressInterval,
                                 @Nonnull DownloadParams.OnDownloadProgress progressCallback) {
      super(delegate);
      this.total = total;
      this.progressInterval = progressInterval;
      this.progressCallback = progressCallback;
    }

    @Override
    public void write(Buffer source, long byteCount) throws IOException {
      super.write(source, byteCount);

      bytesWritten += byteCount;
      long now = System.currentTimeMillis();
      if (now - lastUpdateTime > progressInterval) {
        lastUpdateTime = now;
        progressCallback.onDownloadProgress(total, bytesWritten);
      }
    }
  }
}
