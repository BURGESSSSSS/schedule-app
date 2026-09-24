package com.burgess.schedule.school.sync.golden;

interface IUserService {
    void exit() = 1;
    int getUid() = 2;
    String ping() = 4;
    void destroy() = 16777114;
}
