/*
 * BerryMotes android 
 * Copyright (C) 2013 Daniel Triendl <trellmor@trellmor.com>
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

package com.trellmor.berrymotes.sync;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.cookie.DateParseException;
import org.apache.http.impl.cookie.DateUtils;

import android.content.BroadcastReceiver;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.OperationApplicationException;
import android.content.SharedPreferences;
import android.content.SyncResult;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.http.AndroidHttpClient;
import android.os.Environment;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.trellmor.berrymotes.provider.EmotesContract;
import com.trellmor.berrymotes.SettingsActivity;
import com.trellmor.berrymotes.util.CheckListPreference;
import com.trellmor.berrymotes.util.DownloadException;
import com.trellmor.berrymotes.util.NetworkNotAvailableException;
import com.trellmor.berrymotes.util.StorageNotAvailableException;

public class EmoteDownloader {
	private static final String TAG = EmoteDownloader.class.getName();

	private static final String HOST = "http://berrymotes.pew.cc/";
	private static final String EMOTES = "emotes.json.gz";

	private Context mContext;
	private boolean mDownloadNSFW;
	private boolean mWiFiOnly;
	private Date mLastModified;
	private CheckListPreference mSubreddits;

	private int mNetworkType;
	private boolean mIsConnected;

	private AndroidHttpClient mHttpClient;

	private File mBaseDir;
	private final ContentResolver mContentResolver;
	
	public static final String PREF_SYNC_STATUS = "sync_status";
	public static final String PREF_SYNC_STATUS_MESSAGE = "sync_status_message";
	
	public static final int STATUS_UNKNOW = -1;
	public static final int STATUS_OK = 0;
	public static final int STATUS_FAILED = 1;
	public static final int STATUS_ABORTED = 2;

	public EmoteDownloader(Context context) {
		mContext = context;

		SharedPreferences settings = PreferenceManager
				.getDefaultSharedPreferences(context);
		mDownloadNSFW = settings.getBoolean(SettingsActivity.KEY_SYNC_NSFW,
				false);
		mWiFiOnly = settings.getString(SettingsActivity.KEY_SYNC_CONNECTION,
				SettingsActivity.VALUE_SYNC_CONNECTION_WIFI).equals(
				SettingsActivity.VALUE_SYNC_CONNECTION_WIFI);
		mLastModified = new Date(settings.getLong(
				SettingsActivity.KEY_SYNC_LAST_MODIFIED, 0));
		mSubreddits = new CheckListPreference(settings.getString(
				SettingsActivity.KEY_SYNC_SUBREDDITS,
				SettingsActivity.DEFALT_SYNC_SUBREDDITS),
				SettingsActivity.SEPERATOR_SYNC_SUBREDDITS,
				SettingsActivity.DEFALT_SYNC_SUBREDDITS);

		mBaseDir = mContext.getExternalFilesDir(null);

		mContentResolver = mContext.getContentResolver();
	}

	public void start(SyncResult syncResult) throws InterruptedException {
		setSyncStatus(STATUS_UNKNOW, "");
		
		this.updateNetworkInfo();

		if (!mIsConnected) {
			Log.e(TAG, "Network not available");
			syncResult.stats.numIoExceptions++;
			return;
		}

		// Registers BroadcastReceiver to track network connection changes.
		IntentFilter filter = new IntentFilter(
				ConnectivityManager.CONNECTIVITY_ACTION);
		NetworkReceiver receiver = new NetworkReceiver();
		mContext.registerReceiver(receiver, filter);

		mHttpClient = AndroidHttpClient.newInstance("BerryMotes Android sync");

		try {
			List<EmoteImage> emotes = this.getEmoteList();
			if (emotes != null) {
				this.downloadEmotes(emotes, syncResult);

				this.updateEmotes(emotes, syncResult);

				// If everything is ok, update the last modified date
				if (!syncResult.hasError()) {
					PreferenceManager
							.getDefaultSharedPreferences(mContext)
							.edit()
							.putLong(SettingsActivity.KEY_SYNC_LAST_MODIFIED,
									mLastModified.getTime()).commit();

					setSyncStatus(STATUS_OK, "");
				} else {
					setSyncStatus(STATUS_FAILED, "");
				}
			} else {
				setSyncStatus(STATUS_OK, "");
			}
		} catch (URISyntaxException e) {
			Log.wtf(TAG, "Emotes URL is malformed", e);
			syncResult.stats.numParseExceptions++;
			syncResult.delayUntil = 60 * 60;
			setSyncStatus(STATUS_FAILED, e.getMessage());
			return;
		} catch (IOException e) {
			Log.e(TAG, "Error reading from network: " + e.toString(), e);
			syncResult.stats.numIoExceptions++;
			syncResult.delayUntil = 30 * 60;
			
			if (e instanceof NetworkNotAvailableException) {
				setSyncStatus(STATUS_ABORTED, e.getMessage());
			} else if (e instanceof StorageNotAvailableException) {
				setSyncStatus(STATUS_ABORTED, e.getMessage());
			} else {
				setSyncStatus(STATUS_FAILED, e.getMessage());
			}
			return;
		} catch (RemoteException e) {
			Log.e(TAG, "Error updating database: " + e.toString(), e);
			syncResult.databaseError = true;
			setSyncStatus(STATUS_FAILED, e.getMessage());
		} catch (OperationApplicationException e) {
			Log.e(TAG, "Error updating database: " + e.toString(), e);
			syncResult.databaseError = true;
			setSyncStatus(STATUS_FAILED, e.getMessage());
		} catch (InterruptedException e) {
			setSyncStatus(STATUS_ABORTED, e.getMessage());
			throw e;
		} finally {
			mHttpClient.close();
		}

		// Unregisters BroadcastReceiver at the end
		mContext.unregisterReceiver(receiver);
	}

	private void updateNetworkInfo() {
		ConnectivityManager cm = (ConnectivityManager) mContext
				.getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo networkInfo = cm.getActiveNetworkInfo();

		mIsConnected = networkInfo != null && networkInfo.isConnected();

		if (networkInfo != null) {
			mNetworkType = networkInfo.getType();
		} else {
			mNetworkType = Integer.MIN_VALUE;
		}
	}

	private boolean canDownload() {
		if (!mIsConnected) {
			return false;
		}

		return !(mWiFiOnly && mNetworkType != ConnectivityManager.TYPE_WIFI);
	}

	private void checkCanDownload() throws IOException {
		if (!this.canDownload()) {
			throw new NetworkNotAvailableException(
					"Download currently not possible");
		}
	}

	private boolean isStorageAvailable() {
		String state = Environment.getExternalStorageState();
		return Environment.MEDIA_MOUNTED.equals(state);
	}

	private void checkStorageAvailable() throws IOException {
		if (!this.isStorageAvailable()) {
			throw new StorageNotAvailableException("Storage not available");
		}
	}

	private List<EmoteImage> getEmoteList() throws URISyntaxException,
			IOException, RemoteException, OperationApplicationException {
		List<EmoteImage> emotes = downloadEmoteList();
		if (emotes != null) {
			HashMap<String, EmoteImage> emotesHash = new HashMap<String, EmoteImage>();
			int i = 0;
			while (i < emotes.size()) {
				EmoteImage emote = emotes.get(i);
				if (!mSubreddits.isChecked(emote.getSubreddit())) {
					emotes.remove(i);
					continue;
				} else if (!mDownloadNSFW && emote.isNsfw()) {
					emotes.remove(i);
					continue;
				} else {
					emotesHash.put(emote.getHash(), emote);
				}
				i++;
			}

			Cursor c = mContentResolver.query(
					EmotesContract.Emote.CONTENT_URI_DISTINCT, new String[] {
							EmotesContract.Emote.COLUMN_HASH,
							EmotesContract.Emote.COLUMN_IMAGE }, null, null,
					null);
			if (c != null) {
				ArrayList<ContentProviderOperation> batch = new ArrayList<ContentProviderOperation>();

				if (c.moveToFirst()) {
					final int POS_HASH = c
							.getColumnIndex(EmotesContract.Emote.COLUMN_HASH);
					final int POS_IMAGE = c
							.getColumnIndex(EmotesContract.Emote.COLUMN_IMAGE);
					do {
						String hash = c.getString(POS_HASH);
						if (!emotesHash.containsKey(hash)) {
							batch.add(ContentProviderOperation
									.newDelete(EmotesContract.Emote.CONTENT_URI)
									.withSelection(
											EmotesContract.Emote.COLUMN_HASH
													+ "=?",
											new String[] { hash }).build());

							checkStorageAvailable();
							File file = new File(c.getString(POS_IMAGE));
							if (file.exists()) {
								file.delete();
							}
						}
					} while (c.moveToNext());
				}

				c.close();

				applyBatch(batch);
			}
		}
		return emotes;
	}

	private List<EmoteImage> downloadEmoteList() throws URISyntaxException,
			IOException {
		HttpRequestBase request = new HttpGet();
		request.setURI(new URI(HOST + EMOTES));
		request.setHeader("If-Modified-Since",
				DateUtils.formatDate(mLastModified));

		this.checkCanDownload();
		HttpResponse response = mHttpClient.execute(request);
		switch (response.getStatusLine().getStatusCode()) {
		case 200:
			// Download ok
			Header[] lastModified = response.getHeaders("last-modified");
			if (lastModified.length > 0) {
				try {
					mLastModified = DateUtils.parseDate(lastModified[0]
							.getValue());
				} catch (DateParseException e) {
					Log.e(TAG, "Error parsing last-modified header", e);
				}
			}

			HttpEntity entity = response.getEntity();
			if (entity != null) {
				InputStream is = entity.getContent();
				GZIPInputStream zis = new GZIPInputStream(is);
				try {
					Type mapType = new TypeToken<ArrayList<EmoteImage>>() {
					}.getType();

					Reader reader = new InputStreamReader(zis, "UTF-8");
					ArrayList<EmoteImage> emotes = new Gson().fromJson(reader,
							mapType);
					return emotes;
				} finally {
					zis.close();
					is.close();
				}
			}
			break;
		case 304:
			break;
		default:
			throw new IOException("Unexpected HTTP response: "
					+ response.getStatusLine().getReasonPhrase());
		}
		return null;
	}

	private void downloadEmotes(List<EmoteImage> emotes, SyncResult syncResult)
			throws URISyntaxException, IOException, InterruptedException {
		// Create .nomedia file to stop android from indexing the emote images
		this.checkStorageAvailable();
		File nomedia = new File(mBaseDir, ".nomedia");
		if (!nomedia.exists()) {
			nomedia.getParentFile().mkdirs();
			nomedia.createNewFile();
		}

		int i = 0;
		while (i < emotes.size()) {
			EmoteImage emote = emotes.get(i);
			try {
				if (!downloadEmote(emote)) {
					emotes.remove(i);
					continue;
				}
			} catch (DownloadException e) {
				Log.e(TAG, e.getMessage(), e);
				emotes.remove(i);
				syncResult.stats.numIoExceptions++;
				// No point in retrying straight away
				syncResult.delayUntil = 6 * 60 * 60;
				continue;
			}
			i++;
		}
	}

	private boolean downloadEmote(EmoteImage emote) throws IOException,
			URISyntaxException, InterruptedException {

		Thread.sleep(0);
		this.checkStorageAvailable();
		File file = new File(mBaseDir, emote.getImage());

		if (!file.exists()) {
			file.getParentFile().mkdirs();

			this.checkCanDownload();
			HttpGet request = new HttpGet();
			request.setURI(new URI(HOST + emote.getImage()));
			HttpResponse response = mHttpClient.execute(request);
			if (response.getStatusLine().getStatusCode() != 200) {
				throw new DownloadException("Download failed for \""
						+ emote.getImage()
						+ "\" code: "
						+ String.valueOf(response.getStatusLine()
								.getStatusCode()));
			}

			HttpEntity entity = response.getEntity();
			if (entity == null) {
				throw new DownloadException("Download failed for \""
						+ emote.getImage() + "\"");
			}
			InputStream is = entity.getContent();
			try {
				File tmpFile = new File(file.getAbsolutePath() + ".tmp");
				if (tmpFile.exists())
					tmpFile.delete();
				OutputStream os = new FileOutputStream(tmpFile);
				try {
					byte[] buffer = new byte[1024];
					int read;

					while ((read = is.read(buffer)) != -1) {
						os.write(buffer, 0, read);
					}

					os.flush();
				} finally {
					os.close();
				}
				tmpFile.renameTo(file);
			} finally {
				is.close();
			}
		}

		return file.exists();
	}

	public void updateEmotes(List<EmoteImage> emotes, SyncResult syncResult)
			throws RemoteException, OperationApplicationException {

		// Build map of entries
		HashMap<String, EmoteImage> emoteHash = new HashMap<String, EmoteImage>();
		for (EmoteImage emote : emotes) {
			emoteHash.put(emote.getHash(), emote);
		}

		Cursor c = mContentResolver.query(
				EmotesContract.Emote.CONTENT_URI_DISTINCT, new String[] {
						EmotesContract.Emote._ID,
						EmotesContract.Emote.COLUMN_NAME,
						EmotesContract.Emote.COLUMN_HASH }, null, null, null);
		if (c != null) {
			ArrayList<ContentProviderOperation> batch = new ArrayList<ContentProviderOperation>();

			if (c.moveToFirst()) {
				final int POS_ID = c.getColumnIndex(EmotesContract.Emote._ID);
				final int POS_NAME = c
						.getColumnIndex(EmotesContract.Emote.COLUMN_NAME);
				final int POS_HASH = c
						.getColumnIndex(EmotesContract.Emote.COLUMN_HASH);

				do {
					String hash = c.getString(POS_HASH);
					String name = c.getString(POS_NAME);
					EmoteImage emote = emoteHash.get(hash);
					if (emote != null) {
						if (emote.getNames().contains(name)) {
							emote.getNames().remove(name);
							if (emote.getNames().size() == 0) {
								// Already in db, no need to insert
								emoteHash.remove(hash);
								emotes.remove(emote);
							}
						} else {
							Uri deleteUri = EmotesContract.Emote.CONTENT_URI
									.buildUpon()
									.appendPath(
											Integer.toString(c.getInt(POS_ID)))
									.build();
							batch.add(ContentProviderOperation.newDelete(
									deleteUri).build());
							syncResult.stats.numDeletes++;
						}
					}
				} while (c.moveToNext());
			}

			c.close();

			// Delete all emotes that no longer exist
			applyBatch(batch);
		}

		// Generate batch insert
		ArrayList<ContentProviderOperation> batch = new ArrayList<ContentProviderOperation>();
		String baseDir = mBaseDir.getAbsolutePath() + File.separator;
		for (EmoteImage emote : emotes) {
			for (String name : emote.getNames()) {
				batch.add(ContentProviderOperation
						.newInsert(EmotesContract.Emote.CONTENT_URI)
						.withValue(EmotesContract.Emote.COLUMN_NAME, name)
						.withValue(EmotesContract.Emote.COLUMN_NSFW,
								(emote.isNsfw() ? 1 : 0))
						.withValue(EmotesContract.Emote.COLUMN_APNG,
								(emote.isApng() ? 1 : 0))
						.withValue(EmotesContract.Emote.COLUMN_IMAGE,
								baseDir + emote.getImage())
						.withValue(EmotesContract.Emote.COLUMN_HASH,
								emote.getHash())
						.withValue(EmotesContract.Emote.COLUMN_INDEX,
								emote.getIndex())
						.withValue(EmotesContract.Emote.COLUMN_DELAY,
								emote.getDelay()).build());
				syncResult.stats.numInserts++;
			}
		}

		applyBatch(batch);
	}

	private void applyBatch(ArrayList<ContentProviderOperation> operations)
			throws RemoteException, OperationApplicationException {
		mContentResolver.applyBatch(EmotesContract.CONTENT_AUTHORITY,
				operations);
		mContentResolver.notifyChange(//
				EmotesContract.Emote.CONTENT_URI, // URI where data was modified
				null, // No local observer
				false); // IMPORTANT: Do not sync to network
	}
	
	private void setSyncStatus(int status, String message) {
		SharedPreferences.Editor settings = PreferenceManager
				.getDefaultSharedPreferences(mContext).edit();
		settings.putInt(PREF_SYNC_STATUS, status);
		settings.putString(PREF_SYNC_STATUS_MESSAGE, message);
		settings.commit();
	}

	public class NetworkReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {
			EmoteDownloader.this.updateNetworkInfo();
		}
	}
}
