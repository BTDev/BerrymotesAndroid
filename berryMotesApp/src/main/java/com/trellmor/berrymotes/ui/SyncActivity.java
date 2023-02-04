/*
 * BerryMotes
 * Copyright (C) 2013-2016 Daniel Triendl <trellmor@trellmor.com>
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

package com.trellmor.berrymotes.ui;

import android.app.LoaderManager;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.trellmor.berrymotes.R;
import com.trellmor.berrymotes.provider.LogProvider;
import com.trellmor.berrymotes.sync.SyncService;
import com.trellmor.berrymotes.sync.SyncUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SyncActivity extends AppCompatActivity implements LoaderManager.LoaderCallbacks<Cursor>, SimpleCursorAdapter.ViewBinder {
	private TextView mTextStatus;
	private SimpleCursorAdapter mAdapter;
	private ListView mListLogs;
	SimpleDateFormat mDateFormat = new SimpleDateFormat("[yyyy-MM-dd HH:mm:ss]", Locale.ENGLISH);

	private static  final int LOADER_LOGS = 2000;

	private final Handler mHandler = new Handler();
	private final Runnable mTimerTask = new Runnable() {

		@Override
		public void run() {
			refreshServiceStatus();
			mHandler.postDelayed(this, 5000);
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_sync);

		Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);

		mTextStatus = (TextView) findViewById(R.id.text_status);

		mListLogs = (ListView) findViewById(R.id.list_logs);
		mAdapter = new SimpleCursorAdapter(this, R.layout.item_log, null, new String[] {
				LogProvider.LogsColumns.COLUMN_TIMESTAMP,
				LogProvider.LogsColumns.COLUMN_MESSAGE}, new int[] {
				R.id.text_timestamp,
				R.id.text_message}, 0);
		mAdapter.setViewBinder(this);
		mListLogs.setAdapter(mAdapter);
		getLoaderManager().initLoader(LOADER_LOGS, null, this);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.sync, menu);
		return true;
	}

	@Override
	protected void onStart() {
		super.onStart();

		mHandler.postDelayed(mTimerTask, 0);
	}

	@Override
	protected void onStop() {
		super.onStop();

		mHandler.removeCallbacks(mTimerTask);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		Intent intent;
		switch (item.getItemId()) {
			case android.R.id.home:
				finish();
				return true;
			case R.id.menu_settings:
				intent = new Intent(this, SettingsActivity.class);
				startActivity(intent);
				return true;
		}
		return super.onOptionsItemSelected(item);
	}

	public void buttonSyncClick(View view) {
		sync();
	}

	public void buttonCancelClick(View view) {
		cancel();
	}

	private void sync() {
		SyncUtils.triggerRefresh();
		refreshServiceStatus();
	}

	private void cancel() {
		SyncUtils.cancelSync();
	}

	private void refreshServiceStatus() {
		mTextStatus.setVisibility(View.VISIBLE);

		if (SyncService.isServiceRunning(this)) {
			mTextStatus.setText(R.string.status_running);
		} else {
			mTextStatus.setText(R.string.status_idle);
		}
	}

	@Override
	public Loader onCreateLoader(int id, Bundle args) {

		return new CursorLoader(this, LogProvider.CONTENT_URI_LOGS, new String[] {
				LogProvider.LogsColumns._ID,
				LogProvider.LogsColumns.COLUMN_MESSAGE,
				LogProvider.LogsColumns.COLUMN_TIMESTAMP}, null, null,
				LogProvider.LogsColumns.COLUMN_TIMESTAMP + " DESC");
	}

	@Override
	public void onLoadFinished(Loader loader, Cursor data) {
		mAdapter.swapCursor(data);
	}

	@Override
	public void onLoaderReset(Loader loader) {
		mAdapter.swapCursor(null);
	}

	@Override
	public boolean setViewValue(View view, Cursor cursor, int columnIndex) {
		if (columnIndex == 2) {
			Date date = new Date(cursor.getLong(columnIndex));
			((TextView) view).setText(mDateFormat.format(date));
			return true;
		}
		return false;
	}
}
