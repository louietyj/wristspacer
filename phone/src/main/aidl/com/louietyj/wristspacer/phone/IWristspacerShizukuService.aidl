package com.louietyj.wristspacer.phone;

/**
 * Shizuku user-service bridge that executes privileged IActivityManager calls
 * on behalf of the app (shell UID required for callingPackage spoofing).
 */
interface IWristspacerShizukuService {
    /**
     * Calls IActivityManager.bindServiceInstance with callingPackage="com.android.systemui"
     * so ASI accepts the bind as if SystemUI requested it.
     *
     * @param serviceConnection  IBinder wrapping the app's IServiceConnection dispatcher
     * @param applicationThread  IBinder wrapping the app's IApplicationThread
     * @param intentBytes        Parcel-marshalled Intent targeting the ASI SmartspaceService
     * @return result from bindServiceInstance (>0 on success)
     */
    int bindSmartspaceService(in IBinder serviceConnection, in IBinder applicationThread, in byte[] intentBytes) = 1;

    void destroy() = 2;
}
