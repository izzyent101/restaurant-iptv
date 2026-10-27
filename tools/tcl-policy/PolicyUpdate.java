import android.content.*;
import android.net.Uri;
import android.os.*;
import java.nio.file.*;
import java.lang.reflect.*;
public class PolicyUpdate {
 public static void main(String[] args) throws Exception {
  Object am=Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
  Class<?> api=Class.forName("android.app.IActivityManager");
  Object holder=api.getMethod("getContentProviderExternal",String.class,int.class,IBinder.class,String.class).invoke(am,"com.tcl.providers.config",0,new Binder(),"mmg-policy");
  Object provider=holder.getClass().getField("provider").get(holder);
  ContentValues values=new ContentValues();
  values.put("config_content",new String(Files.readAllBytes(Paths.get(args[0])),"UTF-8"));
  AttributionSource source=new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
  Object count=Class.forName("android.content.IContentProvider").getMethod("update",AttributionSource.class,Uri.class,ContentValues.class,Bundle.class).invoke(provider,source,Uri.parse("content://com.tcl.providers.config/AppBootPolicy"),values,new Bundle());
  System.out.println("Updated AppBootPolicy rows: "+count);
  System.exit(count.equals(1)?0:1);
 }
}
