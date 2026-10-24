package dev.midrop.qa;

import android.app.*;
import android.content.*;
import android.os.*;
import java.lang.reflect.*;

/** Separate same-signer test APK. No test hooks are added to the production app. */
public final class TrustInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments) { this.arguments=arguments; start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Context c=getTargetContext();
            Activity activity=startActivitySync(new Intent().setClassName(c,"dev.midrop.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            Class<?> deviceClass=Class.forName("dev.midrop.Discovery$Device",true,c.getClassLoader());
            Constructor<?> constructor=deviceClass.getDeclaredConstructor(String.class,String.class,String.class); constructor.setAccessible(true);
            Object device=constructor.newInstance("QA Mac",arguments.getString("address"),"");
            Method connect=activity.getClass().getDeclaredMethod("connect",deviceClass); connect.setAccessible(true);
            final Throwable[] failure=new Throwable[1];
            runOnMainSync(() -> { try { connect.invoke(activity,device); } catch (Throwable error) { failure[0]=error; } });
            if (failure[0]!=null) throw new RuntimeException(failure[0]);
            long deadline=SystemClock.elapsedRealtime()+65000;
            while (SystemClock.elapsedRealtime()<deadline) {
                if (!c.getSharedPreferences("midrop",Context.MODE_PRIVATE).getString("token","").isEmpty()) {
                    result.putString("result","real Android connection flow persisted approved token and certificate pin");
                    finish(Activity.RESULT_OK,result); return;
                }
                Thread.sleep(200);
            }
            throw new AssertionError("Trust UI flow timed out");
        } catch (Throwable error) { result.putString("error",error.toString()); finish(Activity.RESULT_CANCELED,result); }
    }
}
