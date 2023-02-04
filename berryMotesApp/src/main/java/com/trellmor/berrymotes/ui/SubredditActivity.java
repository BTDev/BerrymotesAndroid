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

package com.trellmor.berrymotes.ui;

import android.app.FragmentManager;
import android.app.LoaderManager;
import android.app.SearchManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.SearchRecentSuggestions;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.core.internal.view.SupportMenuItem;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CheckedTextView;
import android.widget.ListView;

import com.trellmor.berrymotes.R;
import com.trellmor.berrymotes.provider.SubredditProvider;
import com.trellmor.berrymotes.provider.SubredditSearchSuggestionProvider;

public class SubredditActivity extends AppCompatActivity implements LoaderManager.LoaderCallbacks<Cursor>,SubredditSortDialog.SubredditSortDialogListener {
	private ListView mListSubreddits;
	private SubredditAdapter mAdapter;
	private int mSort = SubredditSortDialog.SORT_NAME;
	private String mQuery = null;

	private static final int LOADER_SUBREDDITS = 1000;
	private static final String ARG_QUERY = "query";
	private static final String ARG_SORT = "sort";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_subreddit);

		Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);

		mListSubreddits = (ListView) findViewById(R.id.list_subreddits);
		mAdapter = new SubredditAdapter(this);
		mListSubreddits.setAdapter(mAdapter);
		mListSubreddits.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
		mListSubreddits.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				CheckedTextView checkView = (CheckedTextView) view;
				ContentValues values = new ContentValues();
				values.put(SubredditProvider.SubredditColumns.COLUMN_ENABLED, (checkView.isChecked()) ? 1 : 0);
				Uri uri = SubredditProvider.CONTENT_URI_SUBREDDITS.buildUpon().appendPath(String.valueOf(id)).build();
				getContentResolver().update(uri, values, null, null);
			}
		});
		getLoaderManager().initLoader(LOADER_SUBREDDITS, null, this);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.subreddit, menu);

		SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
		final SupportMenuItem menuSearch = (SupportMenuItem) menu.findItem(R.id.menu_search);
		final SearchView fSearchView = (SearchView) menuSearch.getActionView();

		fSearchView.setSearchableInfo(searchManager.getSearchableInfo(getComponentName()));
		fSearchView.setIconifiedByDefault(false);

		fSearchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {

			@Override
			public boolean onQueryTextSubmit(String query) {
				searchSubreddits(query, mSort);
				SearchRecentSuggestions suggestions = new SearchRecentSuggestions(SubredditActivity.this,
						SubredditSearchSuggestionProvider.AUTHORITY, SubredditSearchSuggestionProvider.MODE);
				suggestions.saveRecentQuery(query, null);
				fSearchView.clearFocus();
				return true;
			}

			@Override
			public boolean onQueryTextChange(String newText) {
				searchSubreddits(newText, mSort);
				return true;
			}
		});

		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case android.R.id.home:
				finish();
				return true;
			case R.id.menu_sort:
				FragmentManager fm = getFragmentManager();
				SubredditSortDialog dialog = SubredditSortDialog.newInstance(mSort);
				dialog.setSubredditSortDialogListener(this);
				dialog.show(fm, SubredditSortDialog.DIALOG_TAG);
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
	}

	@Override
	public Loader onCreateLoader(int id, Bundle args) {
		String selection = null;
		String[] selectionArgs = null;
		String sort = SubredditProvider.SubredditColumns.COLUMN_NAME + " COLLATE NOCASE ASC";
		if (args != null) {
			if (args.containsKey(ARG_QUERY)) {
				selection = SubredditProvider.SubredditColumns.COLUMN_NAME + " LIKE ?";
				selectionArgs = new String[]{"%" + args.getString(ARG_QUERY).trim() + "%"};
			}
			if (args.containsKey(ARG_SORT)) {
				switch (args.getInt(ARG_SORT)) {
					case SubredditSortDialog.SORT_ADDED:
						sort = SubredditProvider.SubredditColumns.COLUMN_ADDED + " DESC, " + sort;
						break;
					case SubredditSortDialog.SORT_SIZE:
						sort = SubredditProvider.SubredditColumns.COLUMN_SIZE + " DESC, " + sort;
						break;
				}
			}
		}

		return new CursorLoader(this, SubredditProvider.CONTENT_URI_SUBREDDITS, new String[] {
				SubredditProvider.SubredditColumns._ID,
				SubredditProvider.SubredditColumns.COLUMN_NAME,
				SubredditProvider.SubredditColumns.COLUMN_ENABLED,
				SubredditProvider.SubredditColumns.COLUMN_ADDED,
				SubredditProvider.SubredditColumns.COLUMN_SIZE},
				selection, selectionArgs, sort);
	}

	@Override
	public void onLoadFinished(Loader loader, Cursor data) {
		mAdapter.changeCursor(data);

		final int POS_ENABLED = data.getColumnIndex(SubredditProvider.SubredditColumns.COLUMN_ENABLED);
		for (int i = 0; i < mListSubreddits.getCount(); i++) {
			Cursor c = (Cursor) mListSubreddits.getItemAtPosition(i);
			mListSubreddits.setItemChecked(i, c.getInt(POS_ENABLED) == 1);
		}
	}

	@Override
	public void onLoaderReset(Loader loader) {
		mAdapter.swapCursor(null);
	}

	private void searchSubreddits(String query, int sort) {
		mQuery = query;
		mSort = sort;
		Bundle args = new Bundle();
		if (query != null && !"".equals(query)) {
			args.putString(ARG_QUERY, query);
		}
		args.putInt(ARG_SORT, sort);
		getLoaderManager().restartLoader(LOADER_SUBREDDITS, args, this);
	}

	@Override
	public void onSubredditSortDialogFinish(int sort) {
		searchSubreddits(mQuery, sort);
	}
}
