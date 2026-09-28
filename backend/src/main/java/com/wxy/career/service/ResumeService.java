package com.wxy.career.service;

import com.wxy.career.vo.ResumeDetailRespVO;
import com.wxy.career.vo.ResumeListRespVO;
import com.wxy.career.vo.ResumeManualReqVO;
import com.wxy.career.vo.ResumeUpdateReqVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 简历服务。
 *
 * @author wxy
 * @date 2026-09-28
 */
public interface ResumeService {

    /**
     * 上传文件并同步解析。
     *
     * @param file 上传文件
     * @param title 简历标题，可为空，空时从文件名推导
     * @return 简历详情
     */
    ResumeDetailRespVO upload(MultipartFile file, String title);

    /**
     * 在线创建简历。
     *
     * @param reqVO 创建请求
     * @return 简历详情
     */
    ResumeDetailRespVO createManual(ResumeManualReqVO reqVO);

    /**
     * 查询当前用户简历列表。
     *
     * @return 简历列表
     */
    List<ResumeListRespVO> list();

    /**
     * 查询当前用户指定简历详情。
     *
     * @param id 简历 ID
     * @return 简历详情
     */
    ResumeDetailRespVO detail(Long id);

    /**
     * 更新当前用户指定简历。
     *
     * @param id 简历 ID
     * @param reqVO 更新请求
     * @return 更新后的简历详情
     */
    ResumeDetailRespVO update(Long id, ResumeUpdateReqVO reqVO);

    /**
     * 逻辑删除当前用户指定简历。
     *
     * @param id 简历 ID
     */
    void delete(Long id);

    /**
     * 将当前用户指定简历设为默认。
     *
     * @param id 简历 ID
     */
    void setDefault(Long id);
}
