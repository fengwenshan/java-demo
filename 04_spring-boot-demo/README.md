# Spring-boot

## 分层架构

- Controller （控制层/web层）

角色：前端或者接口调用方的"接待员"或"路由器" <br/>
职责：负责接受HTTP请求（如@GetMapping、@PostMapping、）解析请求参数，并调用业务层（Service）处理。处理完成后，讲结果封装成JSON等格式返回给前端<br/>
核心原则：只做"调度"和"参数校验"，绝对不写复杂的业务逻辑（如计算、数据库操作）<br/>
常见注解：@RestController、@RequestMapping

- Entity （实体层/持久化对象）

角色：数据库表的"映射图纸"<br/>
职责：通常与数据库中的表一一对应（一张表对应一个Entity类）。类属性对应表的字段，用于在程序内存和数据库磁盘之间传输数据（ORM映射，如JPA或MyBatis）<br/>
核心原则：纯粹的"数据容器"，通常只包含属性、getter/setter方法，极少包含业务<br/>
常见注解：@Entity（JPA）、@Table、@Id,或MyBatis场景下的普通POJO <br/>

- pojo 只管"装数据"，不关心数据怎么用

```java

```