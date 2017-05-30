/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.dangdang.ddframe.rdb.sharding.parsing.parser.statement.select;

import com.dangdang.ddframe.rdb.sharding.constant.AggregationType;
import com.dangdang.ddframe.rdb.sharding.constant.OrderType;
import com.dangdang.ddframe.rdb.sharding.parsing.lexer.token.Assist;
import com.dangdang.ddframe.rdb.sharding.parsing.lexer.token.DefaultKeyword;
import com.dangdang.ddframe.rdb.sharding.parsing.lexer.token.Symbol;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.SQLParser;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.context.AggregationSelectItem;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.context.CommonSelectItem;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.context.GroupBy;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.context.OrderBy;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.context.SelectItem;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.context.Table;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.exception.SQLParsingUnsupportedException;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.expr.SQLExpr;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.expr.SQLIdentifierExpr;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.expr.SQLNumberExpr;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.expr.SQLPropertyExpr;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.token.ItemsToken;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.token.TableToken;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.statement.SQLStatementParser;
import com.dangdang.ddframe.rdb.sharding.util.SQLUtil;
import com.google.common.base.Optional;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

@Getter(AccessLevel.PROTECTED)
public abstract class AbstractSelectParser implements SQLStatementParser {
    
    private static final String DERIVED_COUNT_ALIAS = "AVG_DERIVED_COUNT_%s";
    
    private static final String DERIVED_SUM_ALIAS = "AVG_DERIVED_SUM_%s";
    
    private static final String ORDER_BY_DERIVED_ALIAS = "ORDER_BY_DERIVED_%s";
    
    private static final String GROUP_BY_DERIVED_ALIAS = "GROUP_BY_DERIVED_%s";
    
    private final SQLParser sqlParser;
    
    private final SelectStatement selectStatement;
    
    @Setter
    private int parametersIndex;
    
    public AbstractSelectParser(final SQLParser sqlParser) {
        this.sqlParser = sqlParser;
        selectStatement = new SelectStatement();
    }
    
    @Override
    public final SelectStatement parse() {
        query();
        selectStatement.getOrderByList().addAll(parseOrderBy());
        customizedSelect();
        appendDerivedColumns();
        return selectStatement;
    }
    
    protected void customizedSelect() {
    }
    
    protected void query() {
        sqlParser.accept(DefaultKeyword.SELECT);
        parseDistinct();
        parseSelectList();
        parseFrom();
        parseWhere();
        parseGroupBy();
        queryRest();
    }
    
    protected final void parseDistinct() {
        if (sqlParser.equalAny(DefaultKeyword.DISTINCT, DefaultKeyword.DISTINCTROW, DefaultKeyword.UNION)) {
            selectStatement.setDistinct(true);
            sqlParser.getLexer().nextToken();
            if (hasDistinctOn() && sqlParser.equalAny(DefaultKeyword.ON)) {
                sqlParser.getLexer().nextToken();
                sqlParser.skipParentheses();
            }
        } else if (sqlParser.equalAny(DefaultKeyword.ALL)) {
            sqlParser.getLexer().nextToken();
        }
    }
    
    protected boolean hasDistinctOn() {
        return false;
    }
    
    protected final void parseSelectList() {
        int index = 1;
        do {
            SelectItem selectItem = parseSelectItem(index);
            selectStatement.getItems().add(selectItem);
            if (selectItem instanceof CommonSelectItem && ((CommonSelectItem) selectItem).isStar()) {
                selectStatement.setContainStar(true);
            }
            index++;
        } while (sqlParser.skipIfEqual(Symbol.COMMA));
        selectStatement.setSelectListLastPosition(sqlParser.getLexer().getCurrentToken().getEndPosition() - sqlParser.getLexer().getCurrentToken().getLiterals().length());
    }
    
    private SelectItem parseSelectItem(final int index) {
        sqlParser.skipIfEqual(DefaultKeyword.CONNECT_BY_ROOT);
        String literals = sqlParser.getLexer().getCurrentToken().getLiterals();
        if (sqlParser.equalAny(Symbol.STAR) || Symbol.STAR.getLiterals().equals(SQLUtil.getExactlyValue(literals))) {
            sqlParser.getLexer().nextToken();
            return new CommonSelectItem(Symbol.STAR.getLiterals(), sqlParser.parseAlias(), true);
        }
        if (sqlParser.skipIfEqual(DefaultKeyword.MAX, DefaultKeyword.MIN, DefaultKeyword.SUM, DefaultKeyword.AVG, DefaultKeyword.COUNT)) {
            return new AggregationSelectItem(sqlParser.skipParentheses(), sqlParser.parseAlias(), index, AggregationType.valueOf(literals.toUpperCase()));
        }
        StringBuilder expression = new StringBuilder();
        // FIXME 无as的alias解析, 应该做成倒数第二个token不是运算符,倒数第一个token是Identifier或char,则为别名, 不过CommonSelectItem类型并不关注expression和alias
        // FIXME 解析xxx.*
        while (!sqlParser.equalAny(DefaultKeyword.AS) && !sqlParser.equalAny(Symbol.COMMA) && !sqlParser.equalAny(DefaultKeyword.FROM) && !sqlParser.equalAny(Assist.END)) {
            String value = sqlParser.getLexer().getCurrentToken().getLiterals();
            int position = sqlParser.getLexer().getCurrentToken().getEndPosition() - value.length();
            expression.append(value);
            sqlParser.getLexer().nextToken();
            if (sqlParser.equalAny(Symbol.DOT)) {
                selectStatement.getSqlTokens().add(new TableToken(position, value));
            }
        }
        return new CommonSelectItem(SQLUtil.getExactlyValue(expression.toString()), sqlParser.parseAlias(), false);
    }
    
    protected void queryRest() {
        if (sqlParser.equalAny(DefaultKeyword.UNION, DefaultKeyword.EXCEPT, DefaultKeyword.INTERSECT, DefaultKeyword.MINUS)) {
            throw new SQLParsingUnsupportedException(sqlParser.getLexer().getCurrentToken().getType());
        }
    }
    
    protected final void parseWhere() {
        if (selectStatement.getTables().isEmpty()) {
            return;
        }
        sqlParser.parseWhere(selectStatement);
        parametersIndex = sqlParser.getParametersIndex();
    }
    
    /**
     * 解析排序.
     *
     * @return 排序上下文
     */
    public final List<OrderBy> parseOrderBy() {
        if (!sqlParser.skipIfEqual(DefaultKeyword.ORDER)) {
            return Collections.emptyList();
        }
        List<OrderBy> result = new LinkedList<>();
        sqlParser.skipIfEqual(DefaultKeyword.SIBLINGS);
        sqlParser.accept(DefaultKeyword.BY);
        do {
            Optional<OrderBy> orderBy = parseSelectOrderByItem();
            if (orderBy.isPresent()) {
                result.add(orderBy.get());
            }
        }
        while (sqlParser.skipIfEqual(Symbol.COMMA));
        return result;
    }
    
    protected Optional<OrderBy> parseSelectOrderByItem() {
        SQLExpr expr = sqlParser.parseExpression(selectStatement);
        OrderType orderByType = OrderType.ASC;
        if (sqlParser.skipIfEqual(DefaultKeyword.ASC)) {
            orderByType = OrderType.ASC;
        } else if (sqlParser.skipIfEqual(DefaultKeyword.DESC)) {
            orderByType = OrderType.DESC;
        }
        OrderBy result;
        if (expr instanceof SQLNumberExpr) {
            result = new OrderBy(((SQLNumberExpr) expr).getNumber().intValue(), orderByType);
        } else if (expr instanceof SQLIdentifierExpr) {
            result = new OrderBy(SQLUtil.getExactlyValue(((SQLIdentifierExpr) expr).getName()), orderByType, getAlias(SQLUtil.getExactlyValue(((SQLIdentifierExpr) expr).getName())));
        } else if (expr instanceof SQLPropertyExpr) {
            SQLPropertyExpr sqlPropertyExpr = (SQLPropertyExpr) expr;
            result = new OrderBy(SQLUtil.getExactlyValue(sqlPropertyExpr.getOwner().getName()), SQLUtil.getExactlyValue(sqlPropertyExpr.getName()), orderByType, 
                    getAlias(SQLUtil.getExactlyValue(sqlPropertyExpr.getOwner().getName()) + "." + SQLUtil.getExactlyValue(sqlPropertyExpr.getName())));
        } else {
            return Optional.absent();
        }
        return Optional.of(result);
    }
    
    protected void parseGroupBy() {
        if (sqlParser.skipIfEqual(DefaultKeyword.GROUP)) {
            sqlParser.accept(DefaultKeyword.BY);
            while (true) {
                addGroupByItem(sqlParser.parseExpression(selectStatement));
                if (!sqlParser.equalAny(Symbol.COMMA)) {
                    break;
                }
                sqlParser.getLexer().nextToken();
            }
            while (sqlParser.equalAny(DefaultKeyword.WITH) || sqlParser.getLexer().getCurrentToken().getLiterals().equalsIgnoreCase("ROLLUP")) {
                sqlParser.getLexer().nextToken();
            }
            if (sqlParser.skipIfEqual(DefaultKeyword.HAVING)) {
                sqlParser.parseExpression(selectStatement);
            }
        } else if (sqlParser.skipIfEqual(DefaultKeyword.HAVING)) {
            sqlParser.parseExpression(selectStatement);
        }
    }
    
    protected final void addGroupByItem(final SQLExpr sqlExpr) {
        OrderType orderByType = OrderType.ASC;
        if (sqlParser.equalAny(DefaultKeyword.ASC)) {
            sqlParser.getLexer().nextToken();
        } else if (sqlParser.skipIfEqual(DefaultKeyword.DESC)) {
            orderByType = OrderType.DESC;
        }
        GroupBy groupBy;
        if (sqlExpr instanceof SQLPropertyExpr) {
            SQLPropertyExpr expr = (SQLPropertyExpr) sqlExpr;
            groupBy = new GroupBy(Optional.of(SQLUtil.getExactlyValue(expr.getOwner().getName())), SQLUtil.getExactlyValue(expr.getName()), orderByType,
                    getAlias(SQLUtil.getExactlyValue(expr.getOwner() + "." + SQLUtil.getExactlyValue(expr.getName()))));
        } else if (sqlExpr instanceof SQLIdentifierExpr) {
            SQLIdentifierExpr expr = (SQLIdentifierExpr) sqlExpr;
            groupBy = new GroupBy(Optional.<String>absent(), SQLUtil.getExactlyValue(expr.getName()), orderByType, getAlias(SQLUtil.getExactlyValue(expr.getName())));
        } else {
            return;
        }
        selectStatement.getGroupByList().add(groupBy);
    }
    
    private Optional<String> getAlias(final String name) {
        if (selectStatement.isContainStar()) {
            return Optional.absent();
        }
        String rawName = SQLUtil.getExactlyValue(name);
        for (SelectItem each : selectStatement.getItems()) {
            if (rawName.equalsIgnoreCase(SQLUtil.getExactlyValue(each.getExpression()))) {
                return each.getAlias();
            }
            if (rawName.equalsIgnoreCase(each.getAlias().orNull())) {
                return Optional.of(rawName);
            }
        }
        return Optional.absent();
    }
    
    public final void parseFrom() {
        if (sqlParser.skipIfEqual(DefaultKeyword.FROM)) {
            parseTable();
        }
    }
    
    public void parseTable() {
        if (sqlParser.equalAny(Symbol.LEFT_PAREN)) {
            throw new UnsupportedOperationException("Cannot support subquery");
        }
        parseTableFactor();
        parseJoinTable();
    }
    
    protected final void parseTableFactor() {
        int beginPosition = sqlParser.getLexer().getCurrentToken().getEndPosition() - sqlParser.getLexer().getCurrentToken().getLiterals().length();
        String literals = sqlParser.getLexer().getCurrentToken().getLiterals();
        sqlParser.getLexer().nextToken();
        // TODO 包含Schema解析
        if (sqlParser.skipIfEqual(Symbol.DOT)) {
            sqlParser.getLexer().nextToken();
            sqlParser.parseAlias();
            return;
        }
        // FIXME 根据shardingRule过滤table
        selectStatement.getSqlTokens().add(new TableToken(beginPosition, literals));
        selectStatement.getTables().add(new Table(SQLUtil.getExactlyValue(literals), sqlParser.parseAlias()));
    }
    
    protected void parseJoinTable() {
        if (sqlParser.skipJoin()) {
            parseTable();
            if (sqlParser.skipIfEqual(DefaultKeyword.ON)) {
                do {
                    parseTableCondition(sqlParser.getLexer().getCurrentToken().getEndPosition());
                    sqlParser.accept(Symbol.EQ);
                    parseTableCondition(sqlParser.getLexer().getCurrentToken().getEndPosition() - sqlParser.getLexer().getCurrentToken().getLiterals().length());
                } while (sqlParser.skipIfEqual(DefaultKeyword.AND));
            } else if (sqlParser.skipIfEqual(DefaultKeyword.USING)) {
                sqlParser.skipParentheses();
            }
            parseJoinTable();
        }
    }
    
    private void parseTableCondition(final int startPosition) {
        SQLExpr sqlExpr = sqlParser.parseExpression();
        if (!(sqlExpr instanceof SQLPropertyExpr)) {
            return;
        }
        SQLPropertyExpr sqlPropertyExpr = (SQLPropertyExpr) sqlExpr;
        for (Table each : selectStatement.getTables()) {
            if (each.getName().equalsIgnoreCase(SQLUtil.getExactlyValue(sqlPropertyExpr.getOwner().getName()))) {
                selectStatement.getSqlTokens().add(new TableToken(startPosition, sqlPropertyExpr.getOwner().getName()));
            }
        }
    }
    
    private void appendDerivedColumns() {
        ItemsToken itemsToken = new ItemsToken(selectStatement.getSelectListLastPosition());
        appendAvgDerivedColumns(itemsToken);
        appendOrderByDerivedColumns(itemsToken);
        appendGroupByDerivedColumns(itemsToken);
        if (!itemsToken.getItems().isEmpty()) {
            selectStatement.getSqlTokens().add(itemsToken);
        }
    }
    
    private void appendAvgDerivedColumns(final ItemsToken itemsToken) {
        int derivedColumnOffset = 0;
        for (SelectItem each : selectStatement.getItems()) {
            if (!(each instanceof AggregationSelectItem) || AggregationType.AVG != ((AggregationSelectItem) each).getAggregationType()) {
                continue;
            }
            AggregationSelectItem avgItem = (AggregationSelectItem) each;
            String countAlias = String.format(DERIVED_COUNT_ALIAS, derivedColumnOffset);
            AggregationSelectItem countItem = new AggregationSelectItem(avgItem.getInnerExpression(), Optional.of(countAlias), -1, AggregationType.COUNT);
            String sumAlias = String.format(DERIVED_SUM_ALIAS, derivedColumnOffset);
            AggregationSelectItem sumItem = new AggregationSelectItem(avgItem.getInnerExpression(), Optional.of(sumAlias), -1, AggregationType.SUM);
            avgItem.getDerivedAggregationSelectItems().add(countItem);
            avgItem.getDerivedAggregationSelectItems().add(sumItem);
            // TODO 将AVG列替换成常数，避免数据库再计算无用的AVG函数
            itemsToken.getItems().add(countItem.getExpression() + " AS " + countAlias + " ");
            itemsToken.getItems().add(sumItem.getExpression() + " AS " + sumAlias + " ");
            derivedColumnOffset++;
        }
    }
    
    private void appendOrderByDerivedColumns(final ItemsToken itemsToken) {
        int derivedColumnOffset = 0;
        for (OrderBy each : selectStatement.getOrderByList()) {
            if (!each.getIndex().isPresent() && !each.getAlias().isPresent() && !selectStatement.isContainStar()) {
                String orderByExpression = each.getOwner().isPresent() ? each.getOwner().get() + "." + each.getName().get() : each.getName().get();
                String alias = String.format(ORDER_BY_DERIVED_ALIAS, derivedColumnOffset++);
                each.setAlias(Optional.of(alias));
                itemsToken.getItems().add(orderByExpression + " AS " + alias + " ");
            }
        }
    }
    
    private void appendGroupByDerivedColumns(final ItemsToken itemsToken) {
        int derivedColumnOffset = 0;
        for (GroupBy each : selectStatement.getGroupByList()) {
            if (!each.getAlias().isPresent() && !selectStatement.isContainStar()) {
                String groupByExpression = each.getOwner().isPresent() ? each.getOwner().get() + "." + each.getName() : each.getName();
                String alias = String.format(GROUP_BY_DERIVED_ALIAS, derivedColumnOffset++);
                each.setAlias(Optional.of(alias));
                itemsToken.getItems().add(groupByExpression + " AS " + alias + " ");
            }
        }
    }
}