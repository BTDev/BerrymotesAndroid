/*
 * BerryMotes
 * Copyright (C) 2014-2016 Daniel Triendl <trellmor@trellmor.com>
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

import com.trellmor.berrymotes.R;

import android.app.DialogFragment;
import android.graphics.drawable.AnimationDrawable;
import android.os.Bundle;
import androidx.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

public class LoadingDialog extends DialogFragment {
	private static final String ARG_MESSAGE = "message";
	public static final String DIALOG_TAG = "fragment_loading";

	private AnimationDrawable mImage;
	private int mMessage;

	public LoadingDialog() {

	}

	static LoadingDialog newInstance(int message) {
		LoadingDialog dialog = new LoadingDialog();
		Bundle args = new Bundle();
		args.putInt(ARG_MESSAGE, message);
		dialog.setArguments(args);
		return dialog;
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setStyle(STYLE_NORMAL, R.style.Theme_Dialog_NoActionBar);
		setCancelable(false);

		mMessage = getArguments().getInt(ARG_MESSAGE);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_loading, container, false);

		TextView textMessage = (TextView) view.findViewById(R.id.text_message);
		textMessage.setText(mMessage);

		ImageView imageLoading = (ImageView) view.findViewById(R.id.image_loading);
		mImage = (AnimationDrawable) getResources().getDrawable(R.drawable.loading);
		imageLoading.setBackgroundDrawable(mImage);

		return view;
	}

	@Override
	public void onStart() {
		super.onStart();
		mImage.start();
	}
}
