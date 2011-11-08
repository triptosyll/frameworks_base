/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.frameworkperf;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * So you thought sync used up your battery life.
 */
public class FrameworkPerfActivity extends Activity
        implements AdapterView.OnItemSelectedListener {
    static final String TAG = "Perf";

    Spinner mFgSpinner;
    Spinner mBgSpinner;
    TextView mTestTime;
    Button mStartButton;
    Button mStopButton;
    CheckBox mLocalCheckBox;
    TextView mLog;
    PowerManager.WakeLock mPartialWakeLock;

    long mMaxRunTime = 5000;
    boolean mStarted;

    final String[] mAvailOpLabels;
    final String[] mAvailOpDescriptions;

    int mFgTestIndex = -1;
    int mBgTestIndex = -1;
    TestService.Op mFgTest;
    TestService.Op mBgTest;
    int mCurOpIndex = 0;
    TestConnection mCurConnection;

    final ArrayList<RunResult> mResults = new ArrayList<RunResult>();

    class TestConnection implements ServiceConnection {
        Messenger mService;

        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            mService = new Messenger(service);
            dispatchCurOp(this);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
        }
    }

    static final int MSG_CONTINUE = 1000;

    final Handler mHandler = new Handler() {
        @Override public void handleMessage(Message msg) {
            switch (msg.what) {
                case TestService.RES_TEST_FINISHED: {
                    Bundle bundle = (Bundle)msg.obj;
                    bundle.setClassLoader(getClassLoader());
                    RunResult res = (RunResult)bundle.getParcelable("res");
                    completeCurOp(res);
                } break;
                case TestService.RES_TERMINATED: {
                    // Give a little time for things to settle down.
                    sendMessageDelayed(obtainMessage(MSG_CONTINUE), 500);
                } break;
                case MSG_CONTINUE: {
                    startCurOp();
                } break;
            }
        }
    };

    final Messenger mMessenger = new Messenger(mHandler);

    public FrameworkPerfActivity() {
        mAvailOpLabels = new String[TestService.mAvailOps.length];
        mAvailOpDescriptions = new String[TestService.mAvailOps.length];
        for (int i=0; i<TestService.mAvailOps.length; i++) {
            TestService.Op op = TestService.mAvailOps[i];
            if (op == null) {
                mAvailOpLabels[i] = "All";
                mAvailOpDescriptions[i] = "All tests";
            } else {
                mAvailOpLabels[i] = op.getName();
                if (mAvailOpLabels[i] == null) {
                    mAvailOpLabels[i] = "Nothing";
                }
                mAvailOpDescriptions[i] = op.getLongName();
            }
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set the layout for this activity.  You can find it
        // in res/layout/hello_activity.xml
        setContentView(R.layout.main);

        mFgSpinner = (Spinner) findViewById(R.id.fgspinner);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, mAvailOpLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mFgSpinner.setAdapter(adapter);
        mFgSpinner.setOnItemSelectedListener(this);
        mBgSpinner = (Spinner) findViewById(R.id.bgspinner);
        adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, mAvailOpLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mBgSpinner.setAdapter(adapter);
        mBgSpinner.setOnItemSelectedListener(this);

        mTestTime = (TextView)findViewById(R.id.testtime);

        mStartButton = (Button)findViewById(R.id.start);
        mStartButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startRunning();
            }
        });
        mStopButton = (Button)findViewById(R.id.stop);
        mStopButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopRunning();
            }
        });
        mStopButton.setEnabled(false);
        mLocalCheckBox = (CheckBox)findViewById(R.id.local);

        mLog = (TextView)findViewById(R.id.log);

        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        mPartialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Scheduler");
        mPartialWakeLock.setReferenceCounted(false);
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (parent == mFgSpinner || parent == mBgSpinner) {
            TestService.Op op = TestService.mAvailOps[position];
            if (parent == mFgSpinner) {
                mFgTestIndex = position;
                mFgTest = op;
                ((TextView)findViewById(R.id.fgtext)).setText(mAvailOpDescriptions[position]);
            } else {
                mBgTestIndex = position;
                mBgTest = op;
                ((TextView)findViewById(R.id.bgtext)).setText(mAvailOpDescriptions[position]);
            }
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopRunning();
        if (mPartialWakeLock.isHeld()) {
            mPartialWakeLock.release();
        }
    }

    void dispatchCurOp(TestConnection conn) {
        if (mCurConnection != conn) {
            Log.w(TAG, "Dispatching on invalid connection: " + conn);
            return;
        }
        TestArgs args = new TestArgs();
        args.maxTime = mMaxRunTime;
        if (mFgTestIndex == 0 && mBgTestIndex == 0) {
            args.combOp = mCurOpIndex;
        } else if (mFgTestIndex != 0 && mBgTestIndex != 0) {
            args.fgOp = mFgTestIndex;
            args.bgOp = mBgTestIndex;
        } else {
            // Skip null test.
            if (mCurOpIndex == 0) {
                mCurOpIndex = 1;
            }
            if (mFgTestIndex != 0) {
                args.fgOp = mFgTestIndex;
                args.bgOp = mCurOpIndex;
            } else {
                args.fgOp = mCurOpIndex;
                args.bgOp = mFgTestIndex;
            }
        }
        Bundle bundle = new Bundle();
        bundle.putParcelable("args", args);
        Message msg = Message.obtain(null, TestService.CMD_START_TEST, bundle);
        msg.replyTo = mMessenger;
        try {
            conn.mService.send(msg);
        } catch (RemoteException e) {
            Log.i(TAG, "Failure communicating with service", e);
        }
    }

    void completeCurOp(RunResult result) {
        log(String.format("%s: fg=%d*%gms/op (%dms) / bg=%d*%gms/op (%dms)",
                result.name, result.fgOps, result.getFgMsPerOp(), result.fgTime,
                result.bgOps, result.getBgMsPerOp(), result.bgTime));
        mResults.add(result);
        if (!mStarted) {
            log("Stop");
            stopRunning();
            return;
        }
        if (mFgTest != null && mBgTest != null) {
            log("Finished");
            stopRunning();
            return;
        }
        if (mFgTest == null && mBgTest == null) {
            mCurOpIndex+=2;
            if (mCurOpIndex >= TestService.mOpPairs.length) {
                log("Finished");
                stopRunning();
                return;
            }
        } else {
            mCurOpIndex++;
            if (mCurOpIndex >= TestService.mAvailOps.length) {
                log("Finished");
                stopRunning();
                return;
            }
        }
        startCurOp();
    }

    void disconnect() {
        if (mCurConnection != null) {
            unbindService(mCurConnection);
            if (mCurConnection.mService != null) {
                Message msg = Message.obtain(null, TestService.CMD_TERMINATE);
                msg.replyTo = mMessenger;
                try {
                    mCurConnection.mService.send(msg);
                } catch (RemoteException e) {
                    Log.i(TAG, "Failure communicating with service", e);
                }
            }
            mCurConnection = null;
        }
    }

    void startCurOp() {
        if (mCurConnection != null) {
            disconnect();
        }
        if (mStarted) {
            mHandler.removeMessages(TestService.RES_TEST_FINISHED);
            mHandler.removeMessages(TestService.RES_TERMINATED);
            mHandler.removeMessages(MSG_CONTINUE);
            mCurConnection = new TestConnection();
            Intent intent;
            if (mLocalCheckBox.isChecked()) {
                intent = new Intent(this, LocalTestService.class);
            } else {
                intent = new Intent(this, TestService.class);
            }
            bindService(intent, mCurConnection, BIND_AUTO_CREATE|BIND_IMPORTANT);
        }
    }

    void startRunning() {
        if (!mStarted) {
            log("Start");
            mStarted = true;
            mStartButton.setEnabled(false);
            mStopButton.setEnabled(true);
            mLocalCheckBox.setEnabled(false);
            mTestTime.setEnabled(false);
            mFgSpinner.setEnabled(false);
            mBgSpinner.setEnabled(false);
            updateWakeLock();
            startService(new Intent(this, SchedulerService.class));
            mCurOpIndex = 0;
            mMaxRunTime = Integer.parseInt(mTestTime.getText().toString());
            mResults.clear();
            startCurOp();
        }
    }

    void stopRunning() {
        if (mStarted) {
            disconnect();
            mStarted = false;
            mStartButton.setEnabled(true);
            mStopButton.setEnabled(false);
            mLocalCheckBox.setEnabled(true);
            mTestTime.setEnabled(true);
            mFgSpinner.setEnabled(true);
            mBgSpinner.setEnabled(true);
            updateWakeLock();
            stopService(new Intent(this, SchedulerService.class));
            for (int i=0; i<mResults.size(); i++) {
                RunResult result = mResults.get(i);
                float fgMsPerOp = result.getFgMsPerOp();
                float bgMsPerOp = result.getBgMsPerOp();
                String fgMsPerOpStr = fgMsPerOp != 0 ? Float.toString(fgMsPerOp) : "";
                String bgMsPerOpStr = bgMsPerOp != 0 ? Float.toString(bgMsPerOp) : "";
                Log.i("PerfRes", "\t" + result.name + "\t" + result.fgOps
                        + "\t" + result.getFgMsPerOp() + "\t" + result.fgTime
                        + "\t" + result.fgLongName + "\t" + result.bgOps
                        + "\t" + result.getBgMsPerOp() + "\t" + result.bgTime
                        + "\t" + result.bgLongName);
            }
        }
    }

    void updateWakeLock() {
        if (mStarted) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (!mPartialWakeLock.isHeld()) {
                mPartialWakeLock.acquire();
            }
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (mPartialWakeLock.isHeld()) {
                mPartialWakeLock.release();
            }
        }
    }

    void log(String s) {
        mLog.setText(mLog.getText() + "\n" + s);
        Log.i(TAG, s);
    }
}