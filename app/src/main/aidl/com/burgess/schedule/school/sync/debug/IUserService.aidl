package com.burgess.schedule.school.sync.debug;

interface IUserService {
    int getUid() = 1;
    String[] findWebViewSockets() = 2;
    int startBridge(String socketName, String token) = 3;
    void stopBridge() = 4;
    void destroy() = 16777114;
}
