package com.raspberryremotecontrol;

import com.jcraft.jsch.*;

import android.util.Log;
import android.view.*;
import android.app.*;
import android.widget.*;
import android.content.*;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.*;
import android.widget.AdapterView.OnItemClickListener;
import java.util.*;
import java.io.*;
import java.text.*;

public class MainActivity extends Activity {

    SharedPreferences prefs = null;
    ChannelExec channel;
    Session session;
    BufferedReader in;
    InfoAdapter adapter;
    private ListView listView;
    Integer refreshrate = 5000;
    List<Profile> Profiles = new ArrayList<Profile>();
    int CurrProfile = -1;
    Info infos[] = new Info[]{
        new Info(R.drawable.hostname, "Hostname", "", -1),
        new Info(R.drawable.distribution, "Distribution", "", -1),
        new Info(R.drawable.kernel, "Kernel", "", -1),
        new Info(R.drawable.firmware, "Firmware", "", -1),
        new Info(R.drawable.cpuheat, "Cpu Temperature", "", -1),
        new Info(R.drawable.uptime, "Uptime", "", -1),
        new Info(R.drawable.ram, "Ram Info", "", -1),
        new Info(R.drawable.cpu, "Cpu", "", -1),
        new Info(R.drawable.storage, "Storage", "", -1)
    };
    private Handler mHandler = new Handler();
    Button shutdownButton, rebootButton;
    MenuItem customCommandItem;
    Boolean infoTaskDone = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        prefs = getSharedPreferences("com.raspberryremotecontrol", MODE_PRIVATE);
        if (prefs.getString("refreshrate", null) != null) {
            refreshrate = Integer.parseInt(prefs.getString("refreshrate", null));
        } else {
            prefs.edit().putString("refreshrate", "5000").commit();
        }
        shutdownButton = (Button) findViewById(R.id.shutdown);
        rebootButton = (Button) findViewById(R.id.reboot);
    }
 
    private void FetchProfiles() {
        String profiles = prefs.getString("profiles", null);
        if (profiles == null) return;
        for (String profile : profiles.split("\\|\\|")) {
            String[] data = profile.split("\\|");
            if (data.length != 4) {
            	continue;
            }
            String Name = data[0];
            String IpAddress = data[1];
            String Username = data[2];
            String Password = data[3];
            Profiles.add(new Profile(Name, IpAddress, Username, Password));
        }
    }

    private void SaveProfiles() {
        String profiles = "";
        for (Profile p : Profiles) {
            profiles += p.Name + "|" + p.IpAddress + "|" + p.Username + "|" + p.Password + "||";
        }

        prefs.edit().putString("profiles", profiles).commit();
    }
 
    int lastChecked = 0;
    private void SelectProfile() {
    	if (Profiles.isEmpty()) {
    		FetchProfiles();
    	}    	
    	if (Profiles.isEmpty()) {
    		CreateNewProfile();
    		return;
    	}

    	final String[] ProfilesName = new String[Profiles.size()];
    	for (int i = 0; i < Profiles.size(); i++) {
    		ProfilesName[i] = Profiles.get(i).Name;
    	}
    	lastChecked = 0;

    	final View dialog_layout = getLayoutInflater().inflate(R.layout.select_profile_dialog_layout, null);
    	final ListView lv = (ListView) dialog_layout.findViewById(R.id.profiles);
    	ArrayAdapter<String> adapter1 = new ArrayAdapter<String>(MainActivity.this, android.R.layout.simple_list_item_single_choice, ProfilesName);
    	lv.setAdapter(adapter1);
    	lv.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
    	lv.setItemChecked(0, true);
    	lv.setOnItemClickListener(new OnItemClickListener() {
    		boolean somethingChecked = false;

    		public void onItemClick(AdapterView<?> arg0, View arg1, int arg2,
    				long arg3) {
    			if (somethingChecked) {
    				ListView lv = (ListView) arg0;
    				TextView tv = (TextView) lv.getChildAt(lastChecked);
    				CheckedTextView cv = (CheckedTextView) tv;
    				cv.setChecked(false);
    			}
    			ListView lv = (ListView) arg0;
    			TextView tv = (TextView) lv.getChildAt(arg2);
    			CheckedTextView cv = (CheckedTextView) tv;
    			if (!cv.isChecked()) {
    				cv.setChecked(true);
    			}
    			lastChecked = arg2;
    			somethingChecked = true;
    		}
    	});

    	final AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this)
    	.setTitle("Profiles");
    	builder.setView(dialog_layout)
    	.setPositiveButton("Select", new DialogInterface.OnClickListener() {
    		public void onClick(DialogInterface dialog, int id) {
    			if (CurrProfile >= 0 && (session != null) && session.isConnected()) {
    				DisconnectSSH();
    			}
    			CurrProfile = lastChecked;

    			for (int i = 0; i < infos.length; i++) {
    				infos[i].Description = "";
    				if (infos[i].ProgressBarProgress != -1) {
    					infos[i].ProgressBarProgress = 0;
    				}
    			}

    			ConnectSSH();
    		}
    	})
    	.setNeutralButton("New", new DialogInterface.OnClickListener() {
    		public void onClick(DialogInterface dialog, int id) {
    			CreateNewProfile();
    		}
    	})
    	.setNegativeButton("Delete", new DialogInterface.OnClickListener() {
    		@Override
    		public void onClick(DialogInterface dialog, int id) {
				if (Profiles.size() == 1) {
					Toast.makeText(getApplicationContext(), "Can't delete the only profile available", Toast.LENGTH_SHORT).show();
					dialog.dismiss();
					SelectProfile();
				} else {
					Profiles.remove(lastChecked);
					SaveProfiles();
					dialog.dismiss();
					SelectProfile();
				}
    			
    		}
    	})
    	.show();
     }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.layout.menu, menu);
        customCommandItem = menu.findItem(R.id.customcommand);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.showprofiles:
                SelectProfile();
                return true;
            case R.id.changerefreshrate:
                ShowChangeRefreshRateDialog();
                return true;
            case R.id.customcommand:
                final AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                final View dialog_layout = getLayoutInflater().inflate(R.layout.sendcustomcommand_dialog_layout, null);
                builder.setTitle("Send custom command");

                final EditText et = (EditText) dialog_layout.findViewById(R.id.customcommand);

                builder.setNegativeButton("Close", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                    }
                });
                builder.setPositiveButton("Send", new DialogInterface.OnClickListener() {
                	public void onClick(DialogInterface dialog, int which) {
                		String output = "";
                		try {
                			output = ExecuteCommand(et.getText().toString(), false);
                		}
                		catch (Exception e) {
                		}
                		if (output.length() > 0) {
                			AlertDialog outDialog = new AlertDialog.Builder(MainActivity.this)
                			.setMessage(output)
                			.setTitle("Output")
                			.setCancelable(true)
                			.setPositiveButton(android.R.string.ok,
                					new DialogInterface.OnClickListener() {
                				public void onClick(DialogInterface dialog, int whichButton) {
                				}
                			})
                			.show();
                			TextView textView = (TextView) outDialog.findViewById(android.R.id.message);
                			textView.setTypeface(android.graphics.Typeface.MONOSPACE);
                			outDialog.show();
                		}
                	}
                });
                final AlertDialog Dialog = builder.create();
                Dialog.setView(dialog_layout);
                Dialog.show();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

   public void ShowChangeRefreshRateDialog() {
    	AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
    	final View dialog_layout = getLayoutInflater().inflate(R.layout.refreshrate_dialog_layout, null);
    	final NumberPicker np = (NumberPicker) dialog_layout.findViewById(R.id.numberPicker1);
    	np.setMaxValue(30);
    	np.setMinValue(1);
    	np.setWrapSelectorWheel(false);

    	np.setValue(Integer.parseInt(prefs.getString("refreshrate", null)) / 1000);

    	builder.setTitle("Change refresh rate");
    	builder.setPositiveButton("Confirm", new DialogInterface.OnClickListener() {
    		public void onClick(DialogInterface dialog, int id) {
    			prefs.edit().putString("refreshrate", Integer.toString(np.getValue() * 1000)).commit();
    			refreshrate = np.getValue() * 1000;
    		}
    	});
    	builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
    		public void onClick(DialogInterface dialog, int id) {
    		}
    	});

    	AlertDialog Dialog = builder.create();
    	Dialog.setView(dialog_layout);
    	Dialog.show();
    }
   
	String profileNameStr = "";
	String ipAddressStr = "";
	String usernameStr = "";
	String passwordStr = "";
    public void CreateNewProfile() {
    	final View dialog_layout = getLayoutInflater().inflate(R.layout.profile_dialog_layout, null);
    	final EditText ProfileName = (EditText) dialog_layout.findViewById(R.id.profilename);
    	ProfileName.setText(profileNameStr);
    	final EditText IpAddress = (EditText) dialog_layout.findViewById(R.id.ipaddress);
    	IpAddress.setText(ipAddressStr);
    	final EditText username = (EditText) dialog_layout.findViewById(R.id.sshusername);
    	username.setText(usernameStr);
    	final EditText password = (EditText) dialog_layout.findViewById(R.id.sshpassword);
    	password.setText(passwordStr);

    	AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
    	builder.setTitle("Create new profile")
    	.setView(dialog_layout)
    	.setPositiveButton("Confirm", new DialogInterface.OnClickListener() {
    		public void onClick(DialogInterface dialog, int id) {
    			profileNameStr = ProfileName.getText().toString();
    			ipAddressStr = IpAddress.getText().toString();
    			usernameStr = username.getText().toString();
    			passwordStr = password.getText().toString();
    			/* Check all fields are filled in */
    			if (profileNameStr.length() == 0 || ipAddressStr.length() == 0 ||
    			    usernameStr.length() == 0 || passwordStr.length() == 0) {
    				Toast.makeText(getApplicationContext(), "Not all fields completed !!!", Toast.LENGTH_SHORT).show();
    				dialog.dismiss();
    				CreateNewProfile();
    			} else {
    				Profiles.add(new Profile(profileNameStr, ipAddressStr, usernameStr, passwordStr));
    				SaveProfiles();
    				profileNameStr = ""; ipAddressStr = ""; usernameStr = ""; passwordStr = "";
    				dialog.dismiss();
    				SelectProfile();
    			}
    		}
    	})
    	.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
    		public void onClick(DialogInterface dialog, int id) {
				profileNameStr = ""; ipAddressStr = ""; usernameStr = ""; passwordStr = "";
    			dialog.dismiss();
    		}
    	})
    	.show();
    }

    public boolean isOnline() {
        ConnectivityManager conMgr = (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = conMgr.getActiveNetworkInfo();

        if (netInfo == null || !netInfo.isConnected() || !netInfo.isAvailable()) {
            return false;
        }
        return true;
    }

    public void BuildList() {
        if (adapter == null) {
            adapter = new InfoAdapter(this,
                    R.layout.listview_item_row, infos);

            listView = (ListView) findViewById(R.id.listView);
            listView.setAdapter(adapter);
        } else {
            adapter.Update(infos);
        }
    }

    private class GetInfoTask extends AsyncTask<Void, Void, Boolean> {
    	protected Boolean doInBackground(Void... voids) {
    		Boolean result = false;
    		infoTaskDone = false;
    		if (isOnline() && (session != null) && session.isConnected()) {
    			try {
    				if (infos[0].Description.equals("")) {
    					String hostname = ExecuteCommand("hostname -f", false);
    					infos[0].Description = hostname;
    				}
    				if (infos[1].Description.equals("")) {
    					String distribution = ExecuteCommand("cat /etc/*-release | grep PRETTY_NAME=", false);
    					distribution = distribution.replace("PRETTY_NAME=\"", "");
    					distribution = distribution.replace("\"", "");
    					infos[1].Description = distribution;
    				}
    				if (infos[2].Description.equals("")) {
    					String kernel = ExecuteCommand("uname -mrs", false);
    					infos[2].Description = kernel;
    				}
    				if (infos[3].Description.equals("")) {
    					String firmware = ExecuteCommand("uname -v", false);
    					infos[3].Description = firmware;
    				}
    				DecimalFormat df = new DecimalFormat("0.0");
    				String cputemp_str = ExecuteCommand("cat /sys/class/thermal/thermal_zone0/temp", false);

    				if (!cputemp_str.isEmpty()) {
    					String cputemp = df.format(Float
    							.parseFloat(cputemp_str) / 1000) + " ¼C";
    					infos[4].Description = cputemp;
    				} else {
    					infos[4].Description = "* not available *";
    				}

    				String uptime_str = ExecuteCommand("cat /proc/uptime", false).split(" ")[0];
    				if (!uptime_str.isEmpty()){
    					Double d = Double.parseDouble(uptime_str);
    					Integer uptimeseconds = d.intValue();
    					String uptime = convertMS(uptimeseconds * 1000);
    					infos[5].Description = uptime;
    				} else {
    					infos[5].Description = "* not available *";
    				}

    				String info = ExecuteCommand("cat /proc/meminfo", false);
    				info = info.replaceAll(" ", "");
    				info = info.replaceAll("kB", "");
    				String[] lines = info.split(System.getProperty("line.separator"));
    				if (lines.length >= 4) {
    					df = new DecimalFormat("0");
    					Integer MemTot = Integer.parseInt(df.format(Integer.parseInt(lines[0].substring(lines[0].indexOf(":") + 1)) / 1024.0f));
    					Integer MemFree = Integer.parseInt(df.format(Integer.parseInt(lines[1].substring(lines[1].indexOf(":") + 1)) / 1024.0f));
    					Integer Buffers = Integer.parseInt(df.format(Integer.parseInt(lines[2].substring(lines[2].indexOf(":") + 1)) / 1024.0f));
    					Integer Cached = Integer.parseInt(df.format(Integer.parseInt(lines[3].substring(lines[3].indexOf(":") + 1)) / 1024.0f));
    					Integer Used = MemTot - MemFree;
    					Integer fMemFree = MemFree + Buffers + Cached;
    					Integer MemUsed = Used - Buffers - Cached;
    					Integer Percentage = Integer.parseInt(df.format((float) ((float) MemUsed / (float) MemTot) * 100.0f));

    					infos[6].Description = "Used: " + MemUsed + " MB\nFree: " + fMemFree + " MB\nTot: " + MemTot + " MB";
    					infos[6].ProgressBarProgress = Percentage;
    				} else {
    					infos[6].Description = "* Mem stats not available *";
    				}

    				df = new DecimalFormat("0.0");
    				String[] loadavg = ExecuteCommand(
    						"cat /proc/loadavg", false).split(" ");
    				if (loadavg.length < 3) {
    					loadavg = new String[3];
    					loadavg[0] = loadavg[1] = loadavg[2] = "*N/A*";
    				}

    				String cpuCurFreq_cmd = ExecuteCommand("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq", false);
    				String cpuCurFreq = "*N/A*";
    				if (!cpuCurFreq_cmd.isEmpty()) {
    					cpuCurFreq = df.format(Float
    							.parseFloat(cpuCurFreq_cmd) / 1000) + " MHz";
    				}

    				String cpuMinFreq_cmd = ExecuteCommand("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq", false);
    				String cpuMinFreq = "*N/A*";
    				if (!cpuMinFreq_cmd.isEmpty()) {
    					cpuMinFreq = df.format(Float
    							.parseFloat(cpuMinFreq_cmd) / 1000) + " MHz";
    				}

    				String cpuMaxFreq_cmd = ExecuteCommand("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq", false);
    				String cpuMaxFreq = "*N/A*";
    				if (!cpuMaxFreq_cmd.isEmpty()) {
    					cpuMaxFreq = df.format(Float
    							.parseFloat(cpuMaxFreq_cmd) / 1000) + " MHz";
    				}

    				infos[7].Description = "Loads\n" + loadavg[0] + " [1 min],  " + loadavg[1] + " [5 min],  " + loadavg[2] + " [15 min]\nRunning at " + cpuCurFreq + "\n(min: " + cpuMinFreq + ",  max: " + cpuMaxFreq + ")";

    				String Drives = ExecuteCommand("df -T | grep -vE \"tmpfs|rootfs|Filesystem|File system\"", false);
    				lines = Drives.split(System.getProperty("line.separator"));

    				infos[8].Description = "";

    				Integer totalSize = 0;
    				Integer usedSize = 0;
    				Integer partSize = 0;
    				Integer partUsed = 0;
    				for (int i = 0; i < lines.length; i++) {
    					String line = lines[i];
    					line = line.replaceAll("\\s+", "|");
    					String[] DriveInfos = line.split("\\|");
    					if (DriveInfos.length >= 7) {
    						String name = DriveInfos[6];
    						partSize = Integer.parseInt(DriveInfos[2]);
    						String total = kConv(partSize);
    						String free = kConv(Integer.parseInt(DriveInfos[4]));
    						partUsed = Integer.parseInt(DriveInfos[3]);
    						String used = kConv(partUsed);
    						String format = DriveInfos[1];
    						totalSize += partSize;
    						usedSize += partUsed;
    						infos[8].Description += name + "\n" + "Free: " + free + ",  Used: " + used + "\nTotal: " + total + ",  Format: " + format + ((i == (lines.length - 1)) ? "" : "\n\n");
    					}
    				}

    				Integer percentage = usedSize * 100 / (totalSize == 0 ? 1 : totalSize);
    				infos[8].ProgressBarProgress = percentage;
    	    		result = true;
    			} catch (Exception e) {
    				Log.d("rpictl", e.getMessage());
    			}
    		}
    		Log.d("rpictl", "getInfoBkgnd done result = " + result);
    		infoTaskDone = true;
    		return result;
    	}

    	protected void onPostExecute(Boolean result) {
    		if (result) {
    			BuildList();
    		} else {
    			Log.d("rpictl", "onPostExecute no result");
    			mHandler.removeCallbacks(mUpdateInfo);
    			DisconnectSSH();
    		}
    	}
    }

    private Runnable mUpdateInfo = new Runnable () {
    	public void run() {
    		mHandler.postDelayed(this, refreshrate);
    		if (infoTaskDone) {
    			new GetInfoTask().execute();
    		}
    	}
    };
        
    private class ConnectTask extends AsyncTask<Profile, Void, String> {
    	protected String doInBackground(Profile... ps) {
    		String result = "";
    		try {
                JSch jsch = new JSch();
                Profile p = ps[0];
                session = jsch.getSession(p.Username, p.IpAddress, 22);
                session.setPassword(p.Password);
                Properties config = new Properties();
                config.put("StrictHostKeyChecking", "no");
                session.setConfig(config);
                session.setTimeout(4000);	// set session connect timeout to 4 seconds
                session.connect();
            } catch (final Exception e) {
            	session = null;
            	result = e.getMessage();
            	if (result.length() == 0) result = "Exception";
            }
    		return result;
    	}
    	
    	protected void onPostExecute(String result) {
    		if (result.length() == 0) {
    			/* OK, we got a connection */
    			Toast.makeText(getApplicationContext(), "Connected", Toast.LENGTH_SHORT).show();
    			mHandler.post(mUpdateInfo);
    	        if (listView != null) listView.setVisibility(View.VISIBLE);
    	        shutdownButton.setEnabled(true);
    	        rebootButton.setEnabled(true);
    	        customCommandItem.setEnabled(true);
    		} else {
    			new AlertDialog.Builder(MainActivity.this)
    			.setTitle("Connection Error")
    			.setMessage(result)
    			.setPositiveButton("OK", null)
                .show();
    		}
    	}
    }
    
    public void ConnectSSH() {
    	Profile p = Profiles.get(CurrProfile);
    	new ConnectTask().execute(p);
    }

    public void DisconnectSSH() {
    	Log.d("rpictl","DisconnectSSH");
    	if (channel != null) channel.disconnect();
        if (session != null) session.disconnect();
        if (listView != null) listView.setVisibility(View.INVISIBLE);
        shutdownButton.setEnabled(false);
        rebootButton.setEnabled(false);
        customCommandItem.setEnabled(false);
        Toast.makeText(getBaseContext(), "Sesion disconnected", Toast.LENGTH_LONG).show();
    }

    public synchronized String ExecuteCommand(String command, Boolean needsRoot) throws Exception {
    	try {
    		if (session.isConnected()) {
    			int exitStatus;
    			channel = (ChannelExec) session.openChannel("exec");
    			in = new BufferedReader(new InputStreamReader(channel.getInputStream()));

    			Profile p = Profiles.get(CurrProfile);
    			String username = p.Username;
    			if (needsRoot && !username.equals("root")) {
    				command = "sudo " + command;
    			}

    			channel.setCommand(command);
    			channel.connect();

    			StringBuilder builder = new StringBuilder();
    			String line = null;
    			while ((line = in.readLine()) != null) {
    				builder.append(line).append(System.getProperty("line.separator"));
    			}
    			String output = builder.toString();
    			
    			Log.d("rpictl", "ExecuteCommand normal return");
    			if (output.lastIndexOf("\n") > 0) {
    				return output.substring(0, output.lastIndexOf("\n"));
    			} else {
    				return output;
    			}
     		}
    	} catch (Exception e) {
    		Log.d("rpictl", "ExecuteCommand exception: " + e.getMessage());
    		throw e;
    	}
    	return "";
    }

    public String convertMS(int ms) {
        int seconds = (int) ((ms / 1000) % 60);
        int minutes = (int) (((ms / 1000) / 60) % 60);
        int hours = (int) ((((ms / 1000) / 60) / 60) % 24);

        String sec, min, hrs;
        if (seconds < 10) {
            sec = "0" + seconds;
        } else {
            sec = "" + seconds;
        }
        if (minutes < 10) {
            min = "0" + minutes;
        } else {
            min = "" + minutes;
        }
        if (hours < 10) {
            hrs = "0" + hours;
        } else {
            hrs = "" + hours;
        }

        if (hours == 0) {
            return min + ":" + sec;
        } else {
            return hrs + ":" + min + ":" + sec;
        }
    }

    public void shutdown(View view) {
    	new AlertDialog.Builder(this)
    	.setTitle("Confirm")
    	.setMessage("Are you sure you want to shutdown your Raspberry Pi?")
    	.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
    		@Override
    		public void onClick(DialogInterface dialog, int which) {
    			try {
    				ExecuteCommand("shutdown -h now", true);
    			}
    			catch (Exception e) {
    			}
    			DisconnectSSH();
    		}
    	})
    	.setNegativeButton("No", null)
    	.show();
    }

    public void reboot(View view) {
    	new AlertDialog.Builder(this)
    	.setTitle("Confirm")
    	.setMessage("Are you sure you want to reboot your Raspberry Pi?")
    	.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
    		@Override
    		public void onClick(DialogInterface dialog, int which) {
    			try {
    				ExecuteCommand("shutdown -r now", true);
    			}
    			catch (Exception e) {
    			}
    			DisconnectSSH();
    		}
    	})
    	.setNegativeButton("No", null)
    	.show();
    }

    public static String kConv(Integer kSize) {
        String[] unit = {" KB", " MB", " GB", " TB"};
        Integer i = 0;
        Float fSize = (float) (kSize * 1.0);
        while (i < 3 && fSize > 1024) {
            i++;
            fSize = fSize / 1024;
        }
        DecimalFormat df = new DecimalFormat("0.00");
        return df.format(fSize) + unit[i];
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mUpdateInfo);
    }

    @Override
    protected void onResume() {
    	super.onResume();
    	if (prefs.getBoolean("firstrun", true)) {
    		CreateNewProfile();
    		prefs.edit().putBoolean("firstrun", false).commit();
    	} else if (CurrProfile == -1) {
    		SelectProfile();
    	} else {
    		/* Reconnect */
    		ConnectSSH();
    	}
    }

    @Override
    protected void onStop() {
    	super.onStop();
    	DisconnectSSH();
    }
}
