/*
 * BerryMotes
 * Copyright (C) 2016 Daniel Triendl <trellmor@trellmor.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.trellmor.berrymotes.api;

import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.google.gson.Gson;
import com.trellmor.berrymotes.R;
import com.trellmor.berrymotes.sync.EmoteDownloader;
import com.trellmor.berrymotes.sync.StreamUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class UploadLogTask extends AsyncTask<Log, Void, Void> {
	private Context mContext;
	private NotificationManager mNotificationManager;
	private NotificationCompat.Builder mNotificationBuilder;
	private static final int NOTIFICATION_UPLOAD = 1;
	public UploadLogTask(Context context) {
		mContext = context;
	}

	@Override
	protected Void doInBackground(Log... params) {
		Log log = params[0];

		createNotification();

		mNotificationBuilder.setProgress(0, 0, true);
		mNotificationManager.notify(NOTIFICATION_UPLOAD, mNotificationBuilder.build());

		try {
			Uri logUri = createLog(log);

			if (logUri != null) {
				URL fileUrl = new URL(logUri.buildUpon()
						.appendEncodedPath("file/")
						.appendQueryParameter("instance", log.getInstance())
						.build().toString());
				if (uploadLog(fileUrl)) {
					setSuccess(true);
				} else {
					setSuccess(false);
				}
			} else {
				setSuccess(false);
			}
		} catch (IOException e) {
			setSuccess(false);
		}

		return null;
	}

	private boolean uploadLog(URL endpoint) throws IOException {
		File temp = File.createTempFile("log", null, mContext.getCacheDir());
		try {
			FileInputStream is;
			is = new FileInputStream(new File(mContext.getFilesDir(), EmoteDownloader.LOG_FILE_NAME));
			try {
				StreamUtils.saveStreamToFile(is, temp);
			} finally {
				is.close();
			}

			final long size = temp.length();
			is = new FileInputStream(temp);
			try {
				HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
				connection.setRequestMethod("POST");
				connection.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");
				connection.setDoInput(false);
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
					connection.setFixedLengthStreamingMode(size);
				} else {
					connection.setFixedLengthStreamingMode((int) size);
				}
				OutputStream os = connection.getOutputStream();
				StreamUtils.copy(is, os, new StreamUtils.ProgressCallback() {
					@Override
					public void onProgress(long done) {
						int percent = (int) ((double)done / (double)size * 100.0);
						mNotificationBuilder.setProgress(100, percent, false);
						mNotificationManager.notify(NOTIFICATION_UPLOAD, mNotificationBuilder.build());
					}
				});
				os.close();
				return connection.getResponseCode() == HttpURLConnection.HTTP_CREATED;
			} finally {
				is.close();
			}
		} catch (IOException e) {
			throw e;
		}
	}

	private void setSuccess(boolean success) {
		mNotificationBuilder.setProgress(0, 0, false);
		mNotificationBuilder.setOngoing(false);
		mNotificationBuilder.setAutoCancel(true);
		if (success) {
			mNotificationBuilder.setContentTitle(mContext.getString(R.string.log_uploaded));
			mNotificationBuilder.setSmallIcon(R.drawable.ic_stat_notify_done);
		} else {
			mNotificationBuilder.setContentTitle(mContext.getString(R.string.log_upload_failed));
			mNotificationBuilder.setSmallIcon(R.drawable.ic_stat_notify_error);
		}
		mNotificationManager.notify(NOTIFICATION_UPLOAD, mNotificationBuilder.build());
	}

	private Uri createLog(Log log) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) new URL(Endpoints.API.LOG).openConnection();
		connection.setRequestMethod("POST");
		connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
		connection.setDoInput(false);
		OutputStream os = connection.getOutputStream();
		Gson gson = new Gson();
		os.write(gson.toJson(log).getBytes("UTF-8"));
		os.close();
		if (connection.getResponseCode() == HttpURLConnection.HTTP_CREATED) {
			String logUri = connection.getHeaderField("Location");
			if (logUri != null) {
				return Uri.parse(logUri);
			}
		}
		return null;
	}

	private void createNotification() {
		mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
		mNotificationBuilder = new NotificationCompat.Builder(mContext);
		mNotificationBuilder.setContentTitle(mContext.getString(R.string.log_uploading));
		mNotificationBuilder.setSmallIcon(R.drawable.ic_stat_notify_upload);
		mNotificationBuilder.setOngoing(true);
		mNotificationBuilder.setAutoCancel(false);
	}
}
