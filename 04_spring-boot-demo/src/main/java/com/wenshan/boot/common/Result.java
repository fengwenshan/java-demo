package com.wenshan.boot.common;

/**
 * 统一响应结果封装
 * <p>
 * 所有接口返回统一结构：{ code, message, data }
 * <ul>
 *   <li>code: 状态码，200 成功，500 失败</li>
 *   <li>message:  提示信息</li>
 *   <li>data: 实际数据（泛型，灵活承载任意类型）</li>
 * </ul>
 */
public class Result<T> {
    private Integer code;
    private String message;
    private T data;

    public Result() {
    }

    public Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 成功（带数据） */
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "操作成功", data);
    }

    /** 成功（无数据） */
    public static <T> Result<T> success() {
        return new Result<>(200, "操作成功", null);
    }

    /** 失败（自定义提示） */
    public static <T> Result<T> error(String message) {
        return new Result<>(500, message, null);
    }

    /** 失败（自定义状态码 + 提示） */
    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
