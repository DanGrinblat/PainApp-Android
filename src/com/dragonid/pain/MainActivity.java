package com.dragonid.pain;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.UUID;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.SensorManager;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.Menu;
import android.widget.TextView;
import android.widget.Toast;

import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.util.PebbleDictionary;

public class MainActivity extends Activity {
	private static final UUID PAIN_UUID = UUID.fromString("730c189a-cb6e-4b42-b121-736109155561");
	private static final int KEY_DATA =      1337;
	private static final int KEY_COUNT =     1338;
	private static final int KEY_TIME =      1339;
	private static final int KEY_PAIN =      1340;
	private static final int KEY_PAIN_TIME = 1341;
	private static final int ACCEL_SAMPLE_COUNT = 4;
	private static final String fileName = "PainResults.txt";
	private static float AND_DEV_STATIC_ALPHA = 0.15f;
	
	private short[] accel_shorts;
	
    private PebbleKit.PebbleDataReceiver mDataReceiver = null;
	private boolean connected;
	private short shorts[];
	private long count;
	private long timestamp;
	private long timestampOld;
	private long painLevel;
	private long painTime;
	private float timeDifference;
	private LowPassFilter lpfAndDev;
	private boolean staticAndDevAlpha = false;
	private float[] acceleration = new float[ACCEL_SAMPLE_COUNT*6];
	private float[] lpfAndDevOutput = new float[3];
	private int aLength;
	private int aChunk  = 1024;
	private char[] aChars;
	private static String root;
	private static File filePathFull;
	private static File filePath;
	private static FileOutputStream outputStream;
	
	@Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = Environment.getExternalStorageDirectory().toString();
        filePath = new File(root + "/PainResults");
        filePath.mkdirs();
        filePathFull = new File(root + "/PainResults/" + fileName);
        //if (!file.mkdirs())
		//  Toast.makeText(getApplicationContext(), "Directory not created", Toast.LENGTH_SHORT).show();
		lpfAndDev = new LPFAndroidDeveloper();
		lpfAndDev.setAlphaStatic(staticAndDevAlpha);
		lpfAndDev.setAlpha(AND_DEV_STATIC_ALPHA);
        setContentView(R.layout.activity_main);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }
    
    public void checkPebbleConnection() {
    	PebbleKit.registerPebbleConnectedReceiver(getApplicationContext(), new BroadcastReceiver() {
  		  @Override
  		  public void onReceive(Context context, Intent intent) {
  			  connected = true;
  			  Toast.makeText(getApplicationContext(), "Pebble connected!", Toast.LENGTH_SHORT).show();
  		  }
  		});

  		PebbleKit.registerPebbleDisconnectedReceiver(getApplicationContext(), new BroadcastReceiver() {
  		  @Override
  		  public void onReceive(Context context, Intent intent) {
  			  connected = false;
    		  Toast.makeText(getApplicationContext(), "Pebble disconnected!", Toast.LENGTH_SHORT).show();
  		  }
  		});
    }

    private void updateUi() {
    	
    	for (int i = 0; i < acceleration.length; i++) {
    		acceleration[i] = (float)shorts[i];
    	}
		
		//If count is 0, samples are not added in order to establish first timestamp. Otherwise, samples are passed through LPF.
		if (count > 1) {
			acceleration[0] = acceleration[0] / SensorManager.GRAVITY_EARTH;
			acceleration[1] = acceleration[1] / SensorManager.GRAVITY_EARTH;
			acceleration[2] = acceleration[2] / SensorManager.GRAVITY_EARTH;
			timeDifference = (float)(timestamp-timestampOld);
			lpfAndDevOutput = lpfAndDev.addSamples(acceleration, timeDifference);
		}
		
		//After filtering out gravity, data is recorded.
		if (count > 1) {
			StringBuilder sb = new StringBuilder();
			TextView data = (TextView) findViewById(R.id.text_data);
			Date timeDate = new Date(timestamp*1000);
			for (int i = 0; i < accel_shorts.length; i++) 
				sb.append(Short.toString(accel_shorts[i]));
			
			DateFormat outFormat = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss");
			outFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
			if (count % 5 == 0)
				data.setText("");
			sb.append("\n");
			for (int i = 0; i < lpfAndDevOutput.length; i++)
				sb.append(Long.toString((long)lpfAndDevOutput[i]) + " ");
			sb.append("\n" + "Count: " + Long.toString(count) + "\n" + "Time: " + outFormat.format(timeDate) + "\n");
			if (painLevel > -1 && painTime > -1) {
				Date painDate = new Date(painTime*1000);
				sb.append("Pain level: " + Long.toString(painLevel) + "\n" + "Pain Time: " + outFormat.format(painDate) + "\n");
				painLevel = -1;
				painTime = -1;
			}
			data.append(sb);
			data.append("\n");
			sb.append("\n");
			
			//Save StringBuilder to file (appends data if file exists)
			if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
				try {
					BufferedWriter bufferedWriter;
					if (filePathFull.exists())
						bufferedWriter = new BufferedWriter(new FileWriter(filePathFull, true));
					else
						bufferedWriter = new BufferedWriter(new FileWriter(filePathFull));
					bufferedWriter.append(sb);
					bufferedWriter.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
				//Refreshes file detection
				MediaScannerConnection.scanFile(this, new String[] { filePathFull.getAbsolutePath() }, null, null);
			}
			else
	  			Toast.makeText(getApplicationContext(), "External Storage not accessible", Toast.LENGTH_SHORT).show();
		}
    }
    
    @Override
    public void onResume() {
    	super.onResume();
      //  checkPebbleConnection(); 
        
        final Handler handler = new Handler();

        mDataReceiver = new PebbleKit.PebbleDataReceiver(PAIN_UUID) {
            @Override
            public void receiveData(final Context context, final int transactionId, final PebbleDictionary data) {
            	if (count > 0)
            		timestampOld = timestamp;
            	
            	accel_shorts = new short[data.getBytes(KEY_DATA).length/2];
            	ByteBuffer.wrap(data.getBytes(KEY_DATA)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(accel_shorts);
            	
            	
            	shorts = new short[data.getBytes(KEY_DATA).length/2];
            	ByteBuffer.wrap(data.getBytes(KEY_DATA)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts);
            	count = data.getUnsignedInteger(KEY_COUNT);
            	//timestamp = data.getInteger(KEY_TIME);
            	//painLevel = data.getInteger(KEY_PAIN);
            	//painTime = data.getInteger(KEY_PAIN_TIME);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        // All data received from the Pebble must be ACK'd, otherwise you'll hit time-outs in the
                        // watch-app which will cause the watch to feel "laggy" during periods of frequent
                        // communication.
                        PebbleKit.sendAckToPebble(context, transactionId);
                        updateUi();
                    }
                });
            }
        };
        PebbleKit.registerReceivedDataHandler(this, mDataReceiver);
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        if (mDataReceiver != null) {
            unregisterReceiver(mDataReceiver);
            mDataReceiver = null;
        }
    }
}
