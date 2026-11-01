package com.navagent.app

import com.amap.api.navi.AMapNaviListener
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.AMapLaneInfo
import com.amap.api.navi.model.AMapModelCross
import com.amap.api.navi.model.AMapNaviCameraInfo
import com.amap.api.navi.model.AMapNaviCross
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.AMapNaviRouteNotifyData
import com.amap.api.navi.model.AMapNaviTrafficFacilityInfo
import com.amap.api.navi.model.AMapServiceAreaInfo
import com.amap.api.navi.model.AimLessModeCongestionInfo
import com.amap.api.navi.model.AimLessModeStat
import com.amap.api.navi.model.NaviInfo

/**
 * AMapNaviListener 有 34 个抽象方法（含 3 个重载的 onCalculateRouteSuccess/Failure）。
 *
 * 直接实现接口会让业务代码被 30 多个空方法淹没，所以先做一个全空实现，
 * AmapNavController 只 override 真正关心的几个。
 *
 * 方法签名逐个用 javap 对着 navi-3dmap-10.0.800 jar 核过，不要凭记忆改。
 */
abstract class NavListenerAdapter : AMapNaviListener {

    override fun onInitNaviFailure() {}
    override fun onInitNaviSuccess() {}
    override fun onStartNavi(p0: Int) {}
    override fun onTrafficStatusUpdate() {}
    override fun onLocationChange(p0: AMapNaviLocation?) {}
    override fun onGetNavigationText(p0: Int, p1: String?) {}
    override fun onGetNavigationText(p0: String?) {}
    override fun onEndEmulatorNavi() {}
    override fun onArriveDestination() {}
    override fun onCalculateRouteFailure(p0: Int) {}
    override fun onReCalculateRouteForYaw() {}
    override fun onReCalculateRouteForTrafficJam() {}
    override fun onArrivedWayPoint(p0: Int) {}
    override fun onGpsOpenStatus(p0: Boolean) {}
    override fun onNaviInfoUpdate(p0: NaviInfo?) {}
    override fun updateCameraInfo(p0: Array<AMapNaviCameraInfo>?) {}
    override fun updateIntervalCameraInfo(p0: AMapNaviCameraInfo?, p1: AMapNaviCameraInfo?, p2: Int) {}
    override fun onServiceAreaUpdate(p0: Array<AMapServiceAreaInfo>?) {}
    override fun showCross(p0: AMapNaviCross?) {}
    override fun hideCross() {}
    override fun showModeCross(p0: AMapModelCross?) {}
    override fun hideModeCross() {}
    override fun showLaneInfo(p0: Array<AMapLaneInfo>?, p1: ByteArray?, p2: ByteArray?) {}
    override fun showLaneInfo(p0: AMapLaneInfo?) {}
    override fun hideLaneInfo() {}
    override fun onCalculateRouteSuccess(p0: IntArray?) {}
    override fun notifyParallelRoad(p0: Int) {}
    override fun OnUpdateTrafficFacility(p0: Array<AMapNaviTrafficFacilityInfo>?) {}
    override fun OnUpdateTrafficFacility(p0: AMapNaviTrafficFacilityInfo?) {}
    override fun updateAimlessModeStatistics(p0: AimLessModeStat?) {}
    override fun updateAimlessModeCongestionInfo(p0: AimLessModeCongestionInfo?) {}
    override fun onPlayRing(p0: Int) {}
    override fun onCalculateRouteSuccess(p0: AMapCalcRouteResult?) {}
    override fun onCalculateRouteFailure(p0: AMapCalcRouteResult?) {}
    override fun onNaviRouteNotify(p0: AMapNaviRouteNotifyData?) {}
    override fun onGpsSignalWeak(p0: Boolean) {}
}
