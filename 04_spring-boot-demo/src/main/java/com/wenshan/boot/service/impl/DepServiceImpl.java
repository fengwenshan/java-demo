package com.wenshan.boot.service.impl;

import com.wenshan.boot.dao.DepDao;
import com.wenshan.boot.dao.DepDaoImpl;
import com.wenshan.boot.pojo.Dep;
import com.wenshan.boot.service.DepService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

// 实现DepService接口
@Service
public class DepServiceImpl implements DepService {

    private final DepDao depDao =  new DepDaoImpl();
    public List<Dep> queryDeps() {
        List<String> depList = depDao.queryDeps();
        return depList.stream().map(line -> {
            String[] parts = line.split(",");
            Integer id = Integer.parseInt(parts[0]);
            String dep = parts[1];
            LocalDateTime update = LocalDateTime.parse(parts[2].trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return new Dep(id, dep, update);
        }).toList();
    }
}
