package com.dragonid.pain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.DateFormat;
import java.util.Date;
import java.util.UUID;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.Menu;
import android.widget.TextView;
import android.widget.Toast;

import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.util.PebbleDictionary;
import com.dragonid.pain.LowPassFilter;

public class MainActivity extends Activity {
	private final static UUID PAIN_UUID = UUID.fromString("730c189a-cb6e-4b42-b121-736109155561");
	private final static int KEY_DATA = 1337;
	private final static int KEY_COUNT = 1338;
	private final static int KEY_TIME = 1339;
	
    private PebbleKit.PebbleDataReceiver mDataReceiver = null;
	private boolean connected;
	private short shorts[];
	private long count;
	private long timestamp;
	private long timestampOld;
	private float timeDifference;
	private LowPassFilter lpfAndDev;
	private static float AND_DEV_STATIC_ALPHA = 0.15f;
	private boolean staticAndDevAlpha = false;
	private float[] acceleration = new float[3];
	private float[] lpfAndDevOutput = new float[3];
	
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
    
    public static long concatenateDigits(short... digits) {
    	   StringBuilder sb = new StringBuilder(digits.length);
    	   for (short digit : digits) {
    	     sb.append(digit);
    	   }
    	   return Long.valueOf(sb.toString());
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
		if (count > 5) {
			TextView data = (TextView) findViewById(R.id.text_data);
			Date date = new Date();
			DateFormat format = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG);
			String formatted = format.format(date);
			date.setTime(timestamp*1000);
			data.append("\n");
			if (count % 10 == 0)
				data.setText("");
			for (int i = 0; i < lpfAndDevOutput.length; i++)
				data.append(Long.toString((long)lpfAndDevOutput[i]) + " ");
			data.append("\n" + " " + "Count: " + Long.toString(count) + " | Time: " + formatted);
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
            	shorts = new short[data.getBytes(KEY_DATA).length/2];
            	ByteBuffer.wrap(data.getBytes(KEY_DATA)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts);
            	count = data.getUnsignedInteger(KEY_COUNT);
            	timestamp = data.getInteger(KEY_TIME);
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
