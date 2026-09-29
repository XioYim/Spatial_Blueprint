package com.xioyim.spatialblueprint.util;

/**
 * 结构文件导入失败，带上一个可以直接显示给玩家的语言键。
 *
 * <p>以前所有失败都归成一句「解析失败」，玩家完全不知道为什么 —— 是文件版本太老、
 * 格式不对、还是根本没读进来。支持四种格式之后失败原因只会更多，必须分得清。</p>
 */
public class StructureImportException extends Exception {

    private final String failKey;

    public StructureImportException(String failKey) {
        super(failKey);
        this.failKey = failKey;
    }

    public StructureImportException(String failKey, Throwable cause) {
        super(failKey, cause);
        this.failKey = failKey;
    }

    /** 失败原因的语言键，界面直接 {@code Component.translatable} 显示。 */
    public String failKey() {
        return failKey;
    }
}
