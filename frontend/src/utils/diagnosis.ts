import type { ResumeDiagnosisResult } from '@/types/assistant'

/** 「另存为新简历」标题后缀。 */
const OPTIMIZED_TITLE_SUFFIX = '-优化版'

/** 简历标题最大长度，与后端 resume.title 及在线创建接口的校验保持一致。 */
const MAX_TITLE_LENGTH = 100

/** 结构化诊断结论的展示模型。 */
export interface DiagnosisCardModel {
  /** 综合得分。 */
  overallScore: number
  /** 得分说明。 */
  scoreSummary: string
  /** 维度评分，至少 4 项。 */
  dimensions: DiagnosisDimensionModel[]
  /** 问题清单。 */
  problems: ResumeDiagnosisResult['problems']
  /** 亮点清单。 */
  highlights: ResumeDiagnosisResult['highlights']
  /** 优化建议。 */
  suggestions: ResumeDiagnosisResult['suggestions']
  /** 优化后的简历正文。 */
  optimizedResume: string
  /** 可能被追问的项目点。 */
  interviewFollowUps: string[]
  /** 「另存为新简历」使用的标题。 */
  saveAsTitle: string
}

/** 规范化后的维度评分。 */
export interface DiagnosisDimensionModel {
  /** 维度名。 */
  name: string
  /** 维度得分。 */
  score: number
  /** 一句话理由。 */
  comment: string
}

/**
 * 把后端下发的诊断结论整理成卡片展示模型。
 *
 * 模型侧字段可能缺项（列表为空、正文为空），这里统一兜底，避免卡片因为某个字段没填就整块渲染失败。
 *
 * @param diagnosis 诊断结论
 * @returns 卡片展示模型
 */
export function toDiagnosisCardModel(diagnosis: ResumeDiagnosisResult): DiagnosisCardModel {
  return {
    overallScore: diagnosis.overallScore,
    scoreSummary: diagnosis.scoreSummary ?? '',
    dimensions: (diagnosis.dimensions ?? []).map((dimension) => ({
      name: dimension.name,
      score: dimension.score,
      comment: dimension.comment ?? '',
    })),
    problems: diagnosis.problems ?? [],
    highlights: diagnosis.highlights ?? [],
    suggestions: diagnosis.suggestions ?? [],
    optimizedResume: diagnosis.optimizedResume ?? '',
    interviewFollowUps: diagnosis.interviewFollowUps ?? [],
    saveAsTitle: buildOptimizedResumeTitle(diagnosis.resumeTitle),
  }
}

/**
 * 由原简历标题派生「另存为新简历」的默认标题。
 *
 * 标题超长时截断，保证能通过后端 100 字校验；原标题为空时退回「优化版简历」。
 *
 * @param resumeTitle 原简历标题
 * @returns 新简历标题
 */
export function buildOptimizedResumeTitle(resumeTitle: string): string {
  const baseTitle = (resumeTitle ?? '').trim()
  const title = baseTitle ? `${baseTitle}${OPTIMIZED_TITLE_SUFFIX}` : `优化版简历`
  return title.length > MAX_TITLE_LENGTH ? title.slice(0, MAX_TITLE_LENGTH) : title
}
