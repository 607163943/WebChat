package com.webchat.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标题清洗逻辑的单元测试。
 *
 * <p>纯函数，不启动 Spring、不调用模型，因此可以放心放进常规测试流程。
 */
class TitleGeneratorTests {

    @Test
    @DisplayName("正常输出原样保留")
    void keepsPlainTitle() {
        assertThat(TitleGenerator.sanitize("数据库表设计")).contains("数据库表设计");
    }

    @Test
    @DisplayName("多行输出只取第一行有内容的部分")
    void takesFirstNonBlankLine() {
        assertThat(TitleGenerator.sanitize("\n\n数据库表设计\n\n解释：因为……"))
                .contains("数据库表设计");
    }

    @Test
    @DisplayName("去掉「标题：」「会话标题:」这类前缀")
    void stripsPrefix() {
        assertThat(TitleGenerator.sanitize("标题：数据库表设计")).contains("数据库表设计");
        assertThat(TitleGenerator.sanitize("会话标题: 数据库表设计")).contains("数据库表设计");
    }

    @Test
    @DisplayName("去掉成对包裹的引号，中英文都处理")
    void stripsWrappingQuotes() {
        assertThat(TitleGenerator.sanitize("\"数据库表设计\"")).contains("数据库表设计");
        assertThat(TitleGenerator.sanitize("“数据库表设计”")).contains("数据库表设计");
        assertThat(TitleGenerator.sanitize("「数据库表设计」")).contains("数据库表设计");
    }

    @Test
    @DisplayName("只去掉一层引号，不误伤成对出现的书名号内容")
    void stripsOnlyOnePairOfQuotes() {
        assertThat(TitleGenerator.sanitize("《数据库表设计》")).contains("数据库表设计");
    }

    @Test
    @DisplayName("超长标题截断到 varchar(100) 放得下的长度")
    void truncatesToColumnLimit() {
        String tooLong = "标".repeat(TitleGenerator.MAX_TITLE_LENGTH + 50);

        Optional<String> title = TitleGenerator.sanitize(tooLong);

        assertThat(title).isPresent();
        assertThat(title.get()).hasSize(TitleGenerator.MAX_TITLE_LENGTH);
    }

    @Test
    @DisplayName("空输入、纯空白、只有前缀都返回空")
    void emptyInputsYieldEmpty() {
        assertThat(TitleGenerator.sanitize(null)).isEmpty();
        assertThat(TitleGenerator.sanitize("   \n  ")).isEmpty();
        assertThat(TitleGenerator.sanitize("标题：")).isEmpty();
    }
}
