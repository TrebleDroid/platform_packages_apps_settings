/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.settings.wifi.details;

import android.app.Fragment;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.IpPrefix;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkBadging;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkInfo;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceCategory;
import android.support.v7.preference.PreferenceScreen;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.view.View;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settings.R;
import com.android.settings.applications.LayoutPreference;
import com.android.settings.core.PreferenceController;
import com.android.settings.core.lifecycle.Lifecycle;
import com.android.settings.core.lifecycle.LifecycleObserver;
import com.android.settings.core.lifecycle.events.OnPause;
import com.android.settings.core.lifecycle.events.OnResume;
import com.android.settings.wifi.WifiDetailPreference;
import com.android.settings.vpn2.ConnectivityManagerWrapper;
import com.android.settingslib.wifi.AccessPoint;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.StringJoiner;

import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL;

/**
 * Controller for logic pertaining to displaying Wifi information for the
 * {@link WifiNetworkDetailsFragment}.
 */
public class WifiDetailPreferenceController extends PreferenceController implements
        LifecycleObserver, OnPause, OnResume {
    private static final String TAG = "WifiDetailsPrefCtrl";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    @VisibleForTesting
    static final String KEY_CONNECTION_DETAIL_PREF = "connection_detail";
    @VisibleForTesting
    static final String KEY_BUTTONS_PREF = "buttons";
    @VisibleForTesting
    static final String KEY_SIGNAL_STRENGTH_PREF = "signal_strength";
    @VisibleForTesting
    static final String KEY_LINK_SPEED = "link_speed";
    @VisibleForTesting
    static final String KEY_FREQUENCY_PREF = "frequency";
    @VisibleForTesting
    static final String KEY_SECURITY_PREF = "security";
    @VisibleForTesting
    static final String KEY_MAC_ADDRESS_PREF = "mac_address";
    @VisibleForTesting
    static final String KEY_IP_ADDRESS_PREF = "ip_address";
    @VisibleForTesting
    static final String KEY_GATEWAY_PREF = "gateway";
    @VisibleForTesting
    static final String KEY_SUBNET_MASK_PREF = "subnet_mask";
    @VisibleForTesting
    static final String KEY_DNS_PREF = "dns";
    @VisibleForTesting
    static final String KEY_IPV6_ADDRESS_CATEGORY = "ipv6_details_category";

    private AccessPoint mAccessPoint;
    private final ConnectivityManagerWrapper mConnectivityManagerWrapper;
    private final ConnectivityManager mConnectivityManager;
    private final Fragment mFragment;
    private final Handler mHandler;
    private LinkProperties mLinkProperties;
    private Network mNetwork;
    private NetworkInfo mNetworkInfo;
    private NetworkCapabilities mNetworkCapabilities;
    private Context mPrefContext;
    private int mRssi;
    private String[] mSignalStr;
    private final WifiConfiguration mWifiConfig;
    private WifiInfo mWifiInfo;
    private final WifiManager mWifiManager;

    // UI elements - in order of appearance
    private Preference mConnectionDetailPref;
    private LayoutPreference mButtonsPref;
    private Button mSignInButton;
    private WifiDetailPreference mSignalStrengthPref;
    private WifiDetailPreference mLinkSpeedPref;
    private WifiDetailPreference mFrequencyPref;
    private WifiDetailPreference mSecurityPref;
    private WifiDetailPreference mMacAddressPref;
    private WifiDetailPreference mIpAddressPref;
    private WifiDetailPreference mGatewayPref;
    private WifiDetailPreference mSubnetPref;
    private WifiDetailPreference mDnsPref;
    private PreferenceCategory mIpv6AddressCategory;

    private final IntentFilter mFilter;
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case WifiManager.NETWORK_STATE_CHANGED_ACTION:
                case WifiManager.RSSI_CHANGED_ACTION:
                    updateInfo();
            }
        }
    };

    private final NetworkRequest mNetworkRequest = new NetworkRequest.Builder()
            .clearCapabilities().addTransportType(TRANSPORT_WIFI).build();

    // Must be run on the UI thread since it directly manipulates UI state.
    private final NetworkCallback mNetworkCallback = new NetworkCallback() {
        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
            if (network.equals(mNetwork) && !lp.equals(mLinkProperties)) {
                mLinkProperties = lp;
                updateIpLayerInfo();
            }
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities nc) {
            if (network.equals(mNetwork) && !nc.equals(mNetworkCapabilities)) {
                mNetworkCapabilities = nc;
                updateIpLayerInfo();
            }
        }

        @Override
        public void onLost(Network network) {
            if (network.equals(mNetwork)) {
                exitActivity();
            }
        }
    };

    public WifiDetailPreferenceController(
            AccessPoint accessPoint,
            ConnectivityManagerWrapper connectivityManagerWrapper,
            Context context,
            Fragment fragment,
            Handler handler,
            Lifecycle lifecycle,
            WifiManager wifiManager) {
        super(context);

        mAccessPoint = accessPoint;
        mConnectivityManager = connectivityManagerWrapper.getConnectivityManager();
        mConnectivityManagerWrapper = connectivityManagerWrapper;
        mFragment = fragment;
        mHandler = handler;
        mNetworkInfo = accessPoint.getNetworkInfo();
        mRssi = accessPoint.getRssi();
        mSignalStr = context.getResources().getStringArray(R.array.wifi_signal);
        mWifiConfig = accessPoint.getConfig();
        mWifiManager = wifiManager;

        mFilter = new IntentFilter();
        mFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        mFilter.addAction(WifiManager.RSSI_CHANGED_ACTION);

        lifecycle.addObserver(this);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String getPreferenceKey() {
        // Returns null since this controller contains more than one Preference
        return null;
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);

        mPrefContext = screen.getPreferenceManager().getContext();

        mConnectionDetailPref = screen.findPreference(KEY_CONNECTION_DETAIL_PREF);

        mButtonsPref = (LayoutPreference) screen.findPreference(KEY_BUTTONS_PREF);
        mSignInButton = (Button) mButtonsPref.findViewById(R.id.right_button);
        mSignInButton.setText(com.android.internal.R.string.network_available_sign_in);
        mSignInButton.setOnClickListener(
            view -> mConnectivityManagerWrapper.startCaptivePortalApp(mNetwork));

        mSignalStrengthPref =
                (WifiDetailPreference) screen.findPreference(KEY_SIGNAL_STRENGTH_PREF);
        mLinkSpeedPref = (WifiDetailPreference) screen.findPreference(KEY_LINK_SPEED);
        mFrequencyPref = (WifiDetailPreference) screen.findPreference(KEY_FREQUENCY_PREF);
        mSecurityPref = (WifiDetailPreference) screen.findPreference(KEY_SECURITY_PREF);

        mMacAddressPref = (WifiDetailPreference) screen.findPreference(KEY_MAC_ADDRESS_PREF);
        mIpAddressPref = (WifiDetailPreference) screen.findPreference(KEY_IP_ADDRESS_PREF);
        mGatewayPref = (WifiDetailPreference) screen.findPreference(KEY_GATEWAY_PREF);
        mSubnetPref = (WifiDetailPreference) screen.findPreference(KEY_SUBNET_MASK_PREF);
        mDnsPref = (WifiDetailPreference) screen.findPreference(KEY_DNS_PREF);

        mIpv6AddressCategory =
                (PreferenceCategory) screen.findPreference(KEY_IPV6_ADDRESS_CATEGORY);

        mSecurityPref.setDetailText(mAccessPoint.getSecurityString(false /* concise */));
    }

    public WifiInfo getWifiInfo() {
        return mWifiInfo;
    }

    @Override
    public void onResume() {
        mConnectivityManagerWrapper.registerNetworkCallback(mNetworkRequest, mNetworkCallback,
                mHandler);
        mNetwork = mWifiManager.getCurrentNetwork();
        mLinkProperties = mConnectivityManager.getLinkProperties(mNetwork);
        mNetworkCapabilities = mConnectivityManager.getNetworkCapabilities(mNetwork);

        updateInfo();

        mContext.registerReceiver(mReceiver, mFilter);
    }

    @Override
    public void onPause() {
        mNetwork = null;
        mLinkProperties = null;
        mNetworkCapabilities = null;
        mContext.unregisterReceiver(mReceiver);
        mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
    }

    private void updateInfo() {
        mNetworkInfo = mConnectivityManager.getNetworkInfo(mNetwork);
        mWifiInfo = mWifiManager.getConnectionInfo();
        if (mNetwork == null || mNetworkInfo == null || mWifiInfo == null) {
            exitActivity();
            return;
        }

        refreshNetworkState();

        // Update Connection Header icon and Signal Strength Preference
        mRssi = mWifiInfo.getRssi();
        refreshRssiViews();

        // MAC Address Pref
        mMacAddressPref.setDetailText(mWifiInfo.getMacAddress());

        // Link Speed Pref
        int linkSpeedMbps = mWifiInfo.getLinkSpeed();
        mLinkSpeedPref.setVisible(linkSpeedMbps >= 0);
        mLinkSpeedPref.setDetailText(mContext.getString(
                R.string.link_speed, mWifiInfo.getLinkSpeed()));

        // Frequency Pref
        final int frequency = mWifiInfo.getFrequency();
        String band = null;
        if (frequency >= AccessPoint.LOWER_FREQ_24GHZ
                && frequency < AccessPoint.HIGHER_FREQ_24GHZ) {
            band = mContext.getResources().getString(R.string.wifi_band_24ghz);
        } else if (frequency >= AccessPoint.LOWER_FREQ_5GHZ
                && frequency < AccessPoint.HIGHER_FREQ_5GHZ) {
            band = mContext.getResources().getString(R.string.wifi_band_5ghz);
        } else {
            Log.e(TAG, "Unexpected frequency " + frequency);
        }
        mFrequencyPref.setDetailText(band);

        updateIpLayerInfo();
    }

    private void exitActivity() {
        if (DEBUG) {
            Log.d(TAG, "Exiting the WifiNetworkDetailsPage");
        }
        mFragment.getActivity().finish();
    }

    private void refreshNetworkState() {
        mAccessPoint.update(mWifiConfig, mWifiInfo, mNetworkInfo);
        mConnectionDetailPref.setTitle(mAccessPoint.getSettingsSummary());
    }

    private void refreshRssiViews() {
        int iconSignalLevel = WifiManager.calculateSignalLevel(
                mRssi, WifiManager.RSSI_LEVELS);
        Drawable wifiIcon = NetworkBadging.getWifiIcon(
                iconSignalLevel, NetworkBadging.BADGING_NONE, mContext.getTheme()).mutate();

        mConnectionDetailPref.setIcon(wifiIcon);

        Drawable wifiIconDark = wifiIcon.getConstantState().newDrawable().mutate();
        wifiIconDark.setTint(mContext.getResources().getColor(
                R.color.wifi_details_icon_color, mContext.getTheme()));
        mSignalStrengthPref.setIcon(wifiIconDark);

        int summarySignalLevel = mAccessPoint.getLevel();
        mSignalStrengthPref.setDetailText(mSignalStr[summarySignalLevel]);
    }

    private void updateIpLayerInfo() {
        mSignInButton.setVisibility(canSignIntoNetwork() ? View.VISIBLE : View.INVISIBLE);

        // Reset all fields
        mIpv6AddressCategory.removeAll();
        mIpv6AddressCategory.setVisible(false);
        mIpAddressPref.setVisible(false);
        mSubnetPref.setVisible(false);
        mGatewayPref.setVisible(false);
        mDnsPref.setVisible(false);

        if (mNetwork == null || mLinkProperties == null) {
            return;
        }
        List<InetAddress> addresses = mLinkProperties.getAddresses();

        // Set IPv4 and IPv6 addresses
        for (int i = 0; i < addresses.size(); i++) {
            InetAddress addr = addresses.get(i);
            if (addr instanceof Inet4Address) {
                mIpAddressPref.setDetailText(addr.getHostAddress());
                mIpAddressPref.setVisible(true);
            } else if (addr instanceof Inet6Address) {
                String ip = addr.getHostAddress();
                Preference pref = new Preference(mPrefContext);
                pref.setKey(ip);
                pref.setTitle(ip);
                pref.setSelectable(false);
                mIpv6AddressCategory.addPreference(pref);
                mIpv6AddressCategory.setVisible(true);
            }
        }

        // Set up IPv4 gateway and subnet mask
        String gateway = null;
        String subnet = null;
        for (RouteInfo routeInfo : mLinkProperties.getRoutes()) {
            if (routeInfo.hasGateway() && routeInfo.getGateway() instanceof Inet4Address) {
                gateway = routeInfo.getGateway().getHostAddress();
            }
            IpPrefix ipPrefix = routeInfo.getDestination();
            if (ipPrefix != null && ipPrefix.getAddress() instanceof Inet4Address
                    && ipPrefix.getPrefixLength() > 0) {
                subnet = ipv4PrefixLengthToSubnetMask(ipPrefix.getPrefixLength());
            }
        }

        if (!TextUtils.isEmpty(subnet)) {
            mSubnetPref.setDetailText(subnet);
            mSubnetPref.setVisible(true);
        }

        if (!TextUtils.isEmpty(gateway)) {
            mGatewayPref.setDetailText(gateway);
            mGatewayPref.setVisible(true);
        }

        // Set IPv4 DNS addresses
        StringJoiner stringJoiner = new StringJoiner(",");
        for (InetAddress dnsServer : mLinkProperties.getDnsServers()) {
            if (dnsServer instanceof Inet4Address) {
                stringJoiner.add(dnsServer.getHostAddress());
            }
        }
        String dnsText = stringJoiner.toString();
        if (!dnsText.isEmpty()) {
            mDnsPref.setDetailText(dnsText);
            mDnsPref.setVisible(true);
        }
    }

    private static String ipv4PrefixLengthToSubnetMask(int prefixLength) {
        try {
            InetAddress all = InetAddress.getByAddress(
                    new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255});
            return NetworkUtils.getNetworkPart(all, prefixLength).getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * Returns whether the network represented by this preference can be forgotten.
     */
    public boolean canForgetNetwork() {
        return mWifiInfo != null && mWifiInfo.isEphemeral() || mWifiConfig != null;
    }

    /**
     * Returns whether the user can sign into the network represented by this preference.
     */
    private boolean canSignIntoNetwork() {
        return mNetworkCapabilities != null && mNetworkCapabilities.hasCapability(
                NET_CAPABILITY_CAPTIVE_PORTAL);
    }

    /**
     * Forgets the wifi network associated with this preference.
     */
    public void forgetNetwork() {
        if (mWifiInfo != null && mWifiInfo.isEphemeral()) {
            mWifiManager.disableEphemeralNetwork(mWifiInfo.getSSID());
        } else if (mWifiConfig != null) {
            if (mWifiConfig.isPasspoint()) {
                mWifiManager.removePasspointConfiguration(mWifiConfig.FQDN);
            } else {
                mWifiManager.forget(mWifiConfig.networkId, null /* action listener */);
            }
        }
        mFragment.getActivity().finish();
    }
}
