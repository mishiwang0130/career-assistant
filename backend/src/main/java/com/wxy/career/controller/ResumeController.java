package com.wxy.career.controller;

import com.wxy.career.common.result.Result;
import com.wxy.career.service.ResumeService;
import com.wxy.career.vo.ResumeDetailRespVO;
import com.wxy.career.vo.ResumeListRespVO;
import com.wxy.career.vo.ResumeManualReqVO;
import com.wxy.career.vo.ResumeUpdateReqVO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 简历管理接口。
 *
 * @author wxy
 * @date 2026-09-28
 */
@RestController
@RequestMapping("/api/resumes")
public class ResumeController {

    /**
     * 简历服务。
     */
    @Resource
    private ResumeService resumeService;

    /**
     * 上传文件并同步解析。
     *
     * @param file 上传文件
     * @param title 简历标题，可为空
     * @return 简历详情
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ResumeDetailRespVO> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title) {
        return Result.success(resumeService.upload(file, title));
    }

    /**
     * 在线创建简历。
     *
     * @param reqVO 创建请求
     * @return 简历详情
     */
    @PostMapping("/manual")
    public Result<ResumeDetailRespVO> createManual(@Valid @RequestBody ResumeManualReqVO reqVO) {
        return Result.success(resumeService.createManual(reqVO));
    }

    /**
     * 查询当前用户简历列表。
     *
     * @return 简历列表
     */
    @GetMapping
    public Result<List<ResumeListRespVO>> list() {
        return Result.success(resumeService.list());
    }

    /**
     * 查询当前用户指定简历详情。
     *
     * @param id 简历 ID
     * @return 简历详情
     */
    @GetMapping("/{id}")
    public Result<ResumeDetailRespVO> detail(@PathVariable Long id) {
        return Result.success(resumeService.detail(id));
    }

    /**
     * 更新当前用户指定简历。
     *
     * @param id 简历 ID
     * @param reqVO 更新请求
     * @return 更新后的简历详情
     */
    @PutMapping("/{id}")
    public Result<ResumeDetailRespVO> update(
            @PathVariable Long id,
            @Valid @RequestBody ResumeUpdateReqVO reqVO) {
        return Result.success(resumeService.update(id, reqVO));
    }

    /**
     * 逻辑删除当前用户指定简历。
     *
     * @param id 简历 ID
     * @return 成功响应
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        resumeService.delete(id);
        return Result.success();
    }

    /**
     * 将当前用户指定简历设为默认。
     *
     * @param id 简历 ID
     * @return 成功响应
     */
    @PutMapping("/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id) {
        resumeService.setDefault(id);
        return Result.success();
    }
}
