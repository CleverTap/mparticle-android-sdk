package com.mparticle;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.location.LocationManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.jar.JarFile;

/**
 * Created by sdozor on 1/9/14.
 */ 

class MPUtility {

    private static String sOpenUDID;
    private static String sBuildUUID;

    public static String getCpuUsage() {
        String str1 = "unknown";
        String str2 = String.valueOf(android.os.Process.myPid());
        java.lang.Process localProcess = null;
        BufferedReader localBufferedReader = null;
        String str3 = null;
        try {
            localProcess = Runtime.getRuntime().exec("top -d 1 -n 1");
            localBufferedReader = new BufferedReader(new InputStreamReader(localProcess.getInputStream()));
            while ((str3 = localBufferedReader.readLine()) != null)
                if (str3.contains(str2)) {
                    String[] arrayOfString = str3.split(" ");
                    if (arrayOfString != null)
                        for (int i = 0; i < arrayOfString.length; i++){
                            if ((arrayOfString[i] != null) && (arrayOfString[i].contains("%"))){
                                str1 = arrayOfString[i];
                                str1 = str1.substring(0, str1.length() - 1);
                                return str1;
                            }
                        }
                }
        }
        catch (IOException localIOException2) {
            Log.w(Constants.LOG_TAG, "Error computing CPU usage");
            localIOException2.printStackTrace();
        }
        finally {
            try {
                 if (localBufferedReader != null)
                    localBufferedReader.close();
                if (localProcess != null)
                    localProcess.destroy();
            }
            catch (IOException localIOException4) {
                Log.w(Constants.LOG_TAG, "Error computing CPU usage");
                localIOException4.printStackTrace();
            }
        }
        return str1;
    }

    public static long getAvailableMemory(Context context) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        activityManager.getMemoryInfo(mi);
        return mi.availMem;
    }

    public static boolean isSystemMemoryLow(Context context) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        activityManager.getMemoryInfo(mi);
        return mi.lowMemory;
    }

    public static long getSystemMemoryThreshold(Context context) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        activityManager.getMemoryInfo(mi);
        return mi.threshold;
    }

    public static String getGpsEnabled(Context context) {
        if (PackageManager.PERMISSION_GRANTED == context
                .checkCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)){
            final LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            return Boolean.toString(manager.isProviderEnabled(LocationManager.GPS_PROVIDER));
        }else{
            return "unknown";
        }
    }

    public static long getAvailableInternalDisk() {
        long availableSpace = -1L;
        File path = Environment.getDataDirectory();
        StatFs stat = new StatFs(path.getPath());
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN_MR1){
            availableSpace = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
        }else{
            availableSpace = (long) stat.getAvailableBlocks() * (long) stat.getBlockSize();
        }
        return availableSpace;
    }

    public static long getAvailableExternalDisk() {
        long availableSpace = -1L;
        File path = Environment.getExternalStorageDirectory();
        StatFs stat = new StatFs(path.getPath());
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN_MR1){
            availableSpace = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
        }else{
            availableSpace = (long) stat.getAvailableBlocks() * (long) stat.getBlockSize();
        }
        return availableSpace;
    }

    public static synchronized String getAndroidID(Context paramContext)
    {
        String str = Settings.Secure.getString(paramContext.getContentResolver(), "android_id");
        return (str == null) || (str.equals("9774d56d682e549c")) || (str.equals("0000000000000000")) || (str.length() < 15) ? null : str;
    }

    public static String getTimeZone()
    {
        return TimeZone.getDefault().getDisplayName(false, 0);
    }

    public static int getOrientation(Context context)
    {
        WindowManager windowManager = (WindowManager) context
                .getSystemService(Context.WINDOW_SERVICE);
        Display getOrient = windowManager.getDefaultDisplay();
        int orientation = Configuration.ORIENTATION_UNDEFINED;
        if(getOrient.getWidth()==getOrient.getHeight()){
            orientation = Configuration.ORIENTATION_SQUARE;
        } else{
            if(getOrient.getWidth() < getOrient.getHeight()){
                orientation = Configuration.ORIENTATION_PORTRAIT;
            }else {
                orientation = Configuration.ORIENTATION_LANDSCAPE;
            }
        }
        return orientation;
    }

    public static long getTotalMemory(Context context) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1){
            return getTotalMemoryJB(context);
        }else{
            return getTotalMemoryPreJB();
        }
    }

    public static long getTotalMemoryJB(Context context){
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        activityManager.getMemoryInfo(mi);
        return mi.totalMem;
    }

    public static long getTotalMemoryPreJB() {
        String str1 = "/proc/meminfo";
        String str2;
        String[] arrayOfString;
        long initial_memory = 0;
        try {
            FileReader localFileReader = new FileReader(str1);
            BufferedReader localBufferedReader = new BufferedReader(localFileReader, 8192);
            str2 = localBufferedReader.readLine();//meminfo
            arrayOfString = str2.split("\\s+");
            for (String num : arrayOfString) {
                Log.i(str2, num + "\t");
            }
            initial_memory = Integer.valueOf(arrayOfString[1]).intValue() * 1024;
            localBufferedReader.close();
            return initial_memory;
        }
        catch (IOException e){
            return -1;
        }
    }

    public static synchronized String getOpenUDID(Context context){
        if (sOpenUDID == null){
            SharedPreferences sharedPrefs = context.getSharedPreferences(
                    Constants.MISC_FILE, Context.MODE_PRIVATE);
            sOpenUDID = sharedPrefs.getString(Constants.PrefKeys.OPEN_UDID, null);
            if (sOpenUDID == null){
                sOpenUDID = getAndroidID(context);
                if (sOpenUDID == null)
                    sOpenUDID = getGeneratedUdid();

                SharedPreferences.Editor editor = sharedPrefs.edit();
                editor.putString(Constants.PrefKeys.OPEN_UDID, sOpenUDID);
                editor.commit();
            }
        }
        return sOpenUDID;
    }

    static String getGeneratedUdid()
    {
        SecureRandom localSecureRandom = new SecureRandom();
        return new BigInteger(64, localSecureRandom).toString(16);
    }

    static String getBuildUUID(Context context) {
        TreeMap localTreeMap = new TreeMap();
        Object localObject1;
        Object localObject2;
        Object localObject3;
        Object localObject4;
        try
        {
            String str = context.getApplicationInfo().sourceDir;
            JarFile localJarFile = new JarFile(str);
            localObject1 = localJarFile.getManifest();
            localJarFile.close();
            localObject2 = ((java.util.jar.Manifest)localObject1).getEntries();
            localObject3 = new java.util.jar.Attributes.Name("SHA1-Digest");
            localObject4 = ((Map)localObject2).entrySet().iterator();
            while (((Iterator)localObject4).hasNext())
            {
                Map.Entry localEntry = (Map.Entry)((Iterator)localObject4).next();
                java.util.jar.Attributes localAttributes = (java.util.jar.Attributes)localEntry.getValue();
                if (localAttributes.containsKey(localObject3)) {
                    localTreeMap.put(localEntry.getKey(), localAttributes.getValue("SHA1-Digest"));
                }
            }
        }
        catch (Exception localException) {}
        if (localTreeMap.size() == 0)
        {
            sBuildUUID = "";
        }
        else
        {
            byte[] arrayOfByte = new byte[16];
            int i = 0;
            localObject1 = localTreeMap.entrySet().iterator();
            while (((Iterator)localObject1).hasNext())
            {
                localObject2 = (Map.Entry)((Iterator)localObject1).next();
                localObject3 = android.util.Base64.decode((String)((Map.Entry)localObject2).getValue(), 0);
                for (int m : (byte[])localObject3)
                {
                    arrayOfByte[i] = ((byte)(arrayOfByte[i] ^ m));
                    i = (i + 1) % 16;
                }
            }
            sBuildUUID = convertBytesToUUID(arrayOfByte, false);
        }
        return sBuildUUID;
    }
    private static String convertBytesToUUID(byte[] paramArrayOfByte, boolean paramBoolean){
        String str = "";
        for (int i = 0; i < 16; i++)
        {
            str = str + String.format("%02x", new Object[] { Byte.valueOf(paramArrayOfByte[i]) });
            if ((paramBoolean) && ((i == 3) || (i == 5) || (i == 7) || (i == 9))) {
                str = str + '-';
            }
        }
        return str;
    }


}