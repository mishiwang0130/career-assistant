package com.wxy.career.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wxy.career.common.mybatis.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 简历实体。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("resume")
public class Resume extends BasePO {

    /**
     * 上传来源。
     */
    public static final String SOURCE_TYPE_UPLOAD = "UPLOAD";

    /**
     * 在线填写或粘贴来源。
     */
    public static final String SOURCE_TYPE_MANUAL = "MANUAL";

    /**
     * 待解析状态。
     */
    public static final String PARSE_STATUS_PENDING = "PENDING";

    /**
     * 解析成功状态。
     */
    public static final String PARSE_STATUS_SUCCESS = "SUCCESS";

    /**
     * 解析失败状态。
     */
    public static final String PARSE_STATUS_FAILED = "FAILED";

    /**
     * 非默认简历。
     */
    public static final int DEFAULT_FLAG_NO = 0;

    /**
     * 默认简历。
     */
    public static final int DEFAULT_FLAG_YES = 1;

    /**
     * 简历 ID，对应 resume.id。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户 ID，对应 resume.user_id。
     */
    private Long userId;

    /**
     * 简历标题，对应 resume.title。
     */
    private String title;

    /**
     * 来源类型，对应 resume.source_type，取值为 UPLOAD 或 MANUAL。
     */
    private String sourceType;

    /**
     * 安全化后的原始文件名，对应 resume.file_name，仅用于展示。
     */
    private String fileName;

    /**
     * MinIO 对象 key，对应 resume.object_key，不对外暴露。
     */
    private String objectKey;

    /**
     * 文件字节数，对应 resume.file_size。
     */
    private Long fileSize;

    /**
     * 小写扩展名，对应 resume.file_ext。
     */
    private String fileExt;

    /**
     * 解析或填写后的简历正文，对应 resume.raw_text。
     */
    private String rawText;

    /**
     * 解析状态，对应 resume.parse_status，取值为 PENDING、SUCCESS 或 FAILED。
     */
    private String parseStatus;

    /**
     * 解析失败原因，对应 resume.parse_error，内容已截断。
     */
    private String parseError;

    /**
     * 是否默认简历，对应 resume.is_default，0-否，1-是。
     */
    @TableField("is_default")
    private Integer defaultFlag;
}
