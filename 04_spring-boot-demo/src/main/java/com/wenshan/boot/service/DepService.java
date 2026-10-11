package com.wenshan.boot.service;

import com.wenshan.boot.dao.DepDao;
import com.wenshan.boot.pojo.Dep;

import java.util.List;

public interface DepService {
    public List<Dep> queryDeps();
}
