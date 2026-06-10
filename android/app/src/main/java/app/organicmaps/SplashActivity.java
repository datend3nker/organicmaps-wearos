package app.organicmaps;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.BLUETOOTH_ADVERTISE;
import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_SCAN;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.window.SplashScreenView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import app.organicmaps.downloader.DownloaderActivity;
import app.organicmaps.intent.Factory;
import app.organicmaps.sdk.location.LocationHelper;
import app.organicmaps.sdk.util.Config;
import app.organicmaps.sdk.util.LocationUtils;
import app.organicmaps.sdk.util.concurrency.UiThread;
import app.organicmaps.sdk.util.log.Logger;
import app.organicmaps.util.SharingUtils;
import app.organicmaps.util.Utils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class SplashActivity extends AppCompatActivity
{
  private static final String TAG = SplashActivity.class.getSimpleName();

  private static final long DELAY = 100;

  private boolean mCanceled = false;

  @SuppressWarnings("NotNullFieldNotInitialized")
  @NonNull
  private ActivityResultLauncher<Intent> mApiRequest;
  @SuppressWarnings("NotNullFieldNotInitialized")
  @NonNull
  private ActivityResultLauncher<String[]> mPermissionRequest;
  @NonNull
  private ActivityResultLauncher<SharingUtils.SharingIntent> mShareLauncher;

  @NonNull
  private final Runnable mInitCoreDelayedTask = this::init;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    UiThread.cancelDelayedTasks(mInitCoreDelayedTask);
    setContentView(R.layout.activity_splash);
    adjustBrandingInfoPadding();

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
      getSplashScreen().setOnExitAnimationListener(SplashScreenView::remove);
    mPermissionRequest = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                                                   result -> {
                                                     Config.setLocationRequested();
                                                     Config.setBluetoothRequested();
                                                   });
    mApiRequest = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
      setResult(result.getResultCode(), result.getData());
      finish();
    });
    mShareLauncher = SharingUtils.RegisterLauncher(this);

    if (MwmApplication.from(this).getDisplayManager().isCarDisplayUsed())
    {
      startActivity(new Intent(this, MapPlaceholderActivity.class));
      finish();
    }
  }

  @Override
  protected void onResume()
  {
    super.onResume();
    if (mCanceled)
      return;

    final List<String> permissions = new ArrayList<>();
    if (!Config.isLocationRequested() && !LocationUtils.checkLocationPermission(this))
    {
      permissions.add(ACCESS_COARSE_LOCATION);
      permissions.add(ACCESS_FINE_LOCATION);
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !Config.isBluetoothRequested())
    {
      if (ContextCompat.checkSelfPermission(this, BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
        permissions.add(BLUETOOTH_CONNECT);
      if (ContextCompat.checkSelfPermission(this, BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
        permissions.add(BLUETOOTH_SCAN);
      if (ContextCompat.checkSelfPermission(this, BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)
        permissions.add(BLUETOOTH_ADVERTISE);
    }

    if (!permissions.isEmpty())
    {
      Logger.d(TAG, "Requesting permissions: " + permissions);
      mPermissionRequest.launch(permissions.toArray(new String[0]));
      return;
    }

    UiThread.runLater(mInitCoreDelayedTask, DELAY);
  }

  @Override
  protected void onPause()
  {
    super.onPause();
    UiThread.cancelDelayedTasks(mInitCoreDelayedTask);
  }

  @Override
  protected void onDestroy()
  {
    super.onDestroy();
    mPermissionRequest.unregister();
    mPermissionRequest = null;
    mApiRequest.unregister();
    mApiRequest = null;
  }

  private void showFatalErrorDialog(@StringRes int titleId, @StringRes int messageId, Exception error)
  {
    mCanceled = true;
    new MaterialAlertDialogBuilder(this, R.style.MwmTheme_AlertDialog)
        .setTitle(titleId)
        .setMessage(messageId)
        .setPositiveButton(
            R.string.report_a_bug,
            (dialog, which) -> Utils.sendBugReport(mShareLauncher, this, "Fatal Error", Log.getStackTraceString(error)))
        .setCancelable(false)
        .show();
  }

  private void init()
  {
    MwmApplication app = MwmApplication.from(this);
    boolean asyncContinue = false;
    try
    {
      asyncContinue = app.initOrganicMaps(this::processNavigation);
    }
    catch (IOException error)
    {
      showFatalErrorDialog(R.string.dialog_error_storage_title, R.string.dialog_error_storage_message, error);
      return;
    }

    if (Config.isFirstLaunch(this) && LocationUtils.checkLocationPermission(this))
    {
      final LocationHelper locationHelper = app.getLocationHelper();
      locationHelper.onEnteredIntoFirstRun();
      if (!locationHelper.isActive())
        locationHelper.start();
    }

    if (!asyncContinue)
      processNavigation();
  }

  // Called from MwmApplication::nativeInitFramework like callback.
  @Keep
  @SuppressWarnings({"unused", "unchecked"})
  public void processNavigation()
  {
    if (isDestroyed())
    {
      Logger.w(TAG, "Ignore late callback from core because activity is already destroyed");
      return;
    }

    // Re-use original intent with the known safe subset of flags to retain security permissions.
    // https://github.com/organicmaps/organicmaps/issues/6944
    final Intent intent = Objects.requireNonNull(getIntent());

    if (isManageSpaceActivity(intent))
    {
      intent.setComponent(new ComponentName(this, DownloaderActivity.class));
    }
    else
    {
      intent.setComponent(new ComponentName(this, DownloadResourcesLegacyActivity.class));
    }

    // FLAG_ACTIVITY_NEW_TASK and FLAG_ACTIVITY_RESET_TASK_IF_NEEDED break the cold start.
    // https://github.com/organicmaps/organicmaps/pull/7287
    // FORWARD_RESULT_FLAG conflicts with the ActivityResultLauncher.
    // https://github.com/organicmaps/organicmaps/issues/8984
    intent.setFlags(intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);

    if (Factory.isStartedForApiResult(intent))
    {
      // Wait for the result from MwmActivity for API callers.
      mApiRequest.launch(intent);
      return;
    }

    Config.setFirstStartDialogSeen(this);
    startActivity(intent);
    finish();
  }

  private boolean isManageSpaceActivity(@NonNull Intent intent)
  {
    var component = intent.getComponent();

    if (!Intent.ACTION_VIEW.equals(intent.getAction()))
      return false;
    if (component == null)
      return false;

    var manageSpaceActivityName = BuildConfig.APPLICATION_ID + ".ManageSpaceActivity";

    return manageSpaceActivityName.equals(component.getClassName());
  }

  private void adjustBrandingInfoPadding()
  {
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.ll__branding_info), (view, insets) -> {
      final Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
      view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(),
                      view.getPaddingBottom() + systemBars.bottom);
      return insets;
    });
  }
}
