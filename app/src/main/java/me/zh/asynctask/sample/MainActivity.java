package me.zh.asynctask.sample;

import android.app.ProgressDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private ImageView vImage;
    private TextView vDownload;

    private ProgressDialog mLoadingDialog;
    private OkHttpClient mClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mLoadingDialog = new ProgressDialog(this);
        mClient = new OkHttpClient.Builder().build();
        findView();
        bindView();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mLoadingDialog != null) {
            if (mLoadingDialog.isShowing()) {
                mLoadingDialog.dismiss();
            }
        }
    }

    private void findView() {
        vImage = findViewById(R.id.image);
        vDownload = findViewById(R.id.download);
    }

    private void bindView() {
        vDownload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = "https://ss0.bdstatic.com/94oJfD_bAAcT8t7mm9GUKT-xh_/timg?image&quality=100&size=b4000_4000&sec=1565684573&di=82491d3ea2a4d5195d6f8bd90eba1953&src=http://image.coolapk.com/picture/2016/1210/459462_1481302685_5118.png.m.jpg";
                DownloadImageTask task = new DownloadImageTask(mClient, new DownloadImageTask.Callback() {
                    @Override
                    public void onStart() {
                        vImage.setImageDrawable(null);
                        mLoadingDialog.setMessage("开始下载...");
                        mLoadingDialog.show();
                    }

                    @Override
                    public void onFinish(Bitmap bitmap) {
                        if (bitmap != null) {
                            vImage.setImageBitmap(bitmap);
                            Toast.makeText(MainActivity.this.getApplicationContext(), "下载成功", Toast.LENGTH_SHORT).show();
                            mLoadingDialog.dismiss();
                        }
                    }
                });
                task.execute(url);
            }
        });
    }

    private static class DownloadImageTask extends AsyncTask<String, Void, Bitmap> {
        private OkHttpClient mClient;
        private Callback mCallback;

        /**
         * 回调接口
         */
        public interface Callback {
            /**
             * 执行前回调
             */
            void onStart();

            /**
             * 执行后回调
             *
             * @param bitmap 执行结果
             */
            void onFinish(Bitmap bitmap);
        }

        public DownloadImageTask(OkHttpClient client, Callback callback) {
            mClient = client;
            mCallback = callback;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            mCallback.onStart();
        }

        @Override
        protected Bitmap doInBackground(String... urls) {
            try {
                String url = urls[0];
                Request request = new Request.Builder()
                        .url(url)
                        .build();
                Call call = mClient.newCall(request);
                Response response = call.execute();
                return BitmapFactory.decodeStream(response.body().byteStream());
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }

        @Override
        protected void onProgressUpdate(Void... values) {
            super.onProgressUpdate(values);
        }

        @Override
        protected void onCancelled(Bitmap bitmap) {
            super.onCancelled(bitmap);
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            super.onPostExecute(bitmap);
            mCallback.onFinish(bitmap);
        }
    }
}