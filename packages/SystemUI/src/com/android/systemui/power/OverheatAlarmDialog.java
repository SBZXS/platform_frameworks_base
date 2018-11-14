/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.power;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Message;
import android.os.UserHandle;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;

/**
 * The alarm dialog shown when the device overheats.
 * When the temperature exceeds a threshold, we're showing this dialog to notify the user.
 * Once the dialog shows, a sound and vibration will be played until the user touches the dialog.
 */
public class OverheatAlarmDialog extends AlertDialog {
    private static final String TAG = "OverheatAlarmDialog";

    private final Context mContext;
    private final OverheatDialogDelegate mOverheatDialogDelegate;
    private final View mContentView, mTitleView;

    private boolean mHasUserInteracted;

    private OverheatAlarmDialog.PowerEventReceiver mPowerEventReceiver;


    /**
     * OverheatAlarmDialog should appear over system panels and keyguard.
     */
    public OverheatAlarmDialog(Context context) {
        super(context, R.style.Theme_SystemUI_Dialog_Alert);
        mContext = context;
        mOverheatDialogDelegate = new OverheatDialogDelegate();

        // Setup custom views, the main purpose of building custom title and message views is to
        // inject AccessibilityDelegate handling beep sound and talkback mix up problem.
        final LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        mContentView = inflater.inflate(R.layout.overheat_dialog_content, null);
        mTitleView = inflater.inflate(R.layout.overheat_dialog_title, null);
        setView(mContentView);
        setCustomTitle(mTitleView);

        setupDialog();
    }

    @Override
    public void dismiss() {
        mContext.unregisterReceiver(mPowerEventReceiver);
        super.dismiss();
    }

    private void setupDialog() {
        getWindow().getAttributes().privateFlags |=
                WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        getWindow().setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Register ACTION_SCREEN_OFF for power Key event.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mPowerEventReceiver = new OverheatAlarmDialog.PowerEventReceiver();
        mContext.registerReceiverAsUser(mPowerEventReceiver, UserHandle.CURRENT, filter, null,
                null);
    }

    @Override
    public void setButton(int whichButton, CharSequence text, Message msg) {
        mContentView.findViewById(R.id.textSpacerNoButtons).setVisibility(View.INVISIBLE);
        super.setButton(whichButton, text, msg);
    }

    @Override
    public void setTitle(CharSequence title) {
        if (mTitleView == null) {
            super.setTitle(title);
            return;
        }
        final TextView titleTextView = mTitleView.findViewById(R.id.alertTitle);
        if (titleTextView != null) {
            titleTextView.setText(title);
            titleTextView.setAccessibilityDelegate(mOverheatDialogDelegate);
        }
    }

    @Override
    public void setIconAttribute(int attrId) {
        if (mTitleView == null) {
            super.setIconAttribute(attrId);
            return;
        }
        final ImageView iconView = mTitleView.findViewById(R.id.icon);
        if (iconView != null) {
            final TypedValue out = new TypedValue();
            mContext.getTheme().resolveAttribute(attrId, out, true);
            iconView.setImageResource(out.resourceId);
            iconView.setContentDescription(mContext.getString(android.R.string.dialog_alert_title));
        }
    }

    @Override
    public void setMessage(CharSequence message) {
        if (mContentView == null) {
            super.setMessage(message);
            return;
        }
        final TextView messageView = mContentView.findViewById(R.id.message);
        if (messageView != null) {
            messageView.setFocusable(true);
            messageView.setText(message);
            messageView.setAccessibilityDelegate(mOverheatDialogDelegate);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!mHasUserInteracted) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_VOLUME_UP:
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                case KeyEvent.KEYCODE_BACK:
                    notifyAlarmBeepSoundChange();
                    mHasUserInteracted = true;
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // Stop beep sound when touch alarm dialog.
        if (!mHasUserInteracted) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN
                    || ev.getAction() == MotionEvent.ACTION_OUTSIDE) {
                notifyAlarmBeepSoundChange();
                mHasUserInteracted = true;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    protected void notifyAlarmBeepSoundChange() {
        mContext.sendBroadcast(new Intent(Intent.ACTION_ALARM_CHANGED).setPackage(
                mContext.getPackageName())
                .setFlags(Intent.FLAG_RECEIVER_FOREGROUND));
    }

    private class PowerEventReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mHasUserInteracted) {
                notifyAlarmBeepSoundChange();
                mHasUserInteracted = true;
            }
        }
    }

    /**
     * Implement AccessibilityDelegate to stop beep sound while title or message view get
     * accessibility focus, in case the alarm beep sound mix up talk back description.
     */
    private class OverheatDialogDelegate extends View.AccessibilityDelegate {
        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle args) {
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS && !mHasUserInteracted) {
                notifyAlarmBeepSoundChange();
                mHasUserInteracted = true;
            }
            return super.performAccessibilityAction(host, action, args);
        }
    }
}
