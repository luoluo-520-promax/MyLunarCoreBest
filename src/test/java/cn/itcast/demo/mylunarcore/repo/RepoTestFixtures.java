package cn.itcast.demo.mylunarcore.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * repo 包测试用公共数据与 JdbcTemplate 模拟工具。
 */
final class RepoTestFixtures {

    private RepoTestFixtures() {
    }

    static ResultSet mockResultSet(Consumer<ResultSetBuilder> setup) {
        ResultSet rs = mock(ResultSet.class);
        setup.accept(new ResultSetBuilder(rs));
        return rs;
    }

    static void stubQueryRows(JdbcTemplate jdbc, List<ResultSet> rows) {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> mapRows(inv.getArgument(1), rows));
        when(jdbc.query(anyString(), any(RowMapper.class), any()))
                .thenAnswer(inv -> mapRows(inv.getArgument(1), rows));
    }

    static void stubQueryForObject(JdbcTemplate jdbc, ResultSet row) {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> mapRow(inv.getArgument(1), row));
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any()))
                .thenAnswer(inv -> mapRow(inv.getArgument(1), row));
    }

    static void stubQueryForObjectThrows(JdbcTemplate jdbc, RuntimeException ex) {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any()))
                .thenThrow(ex);
    }

    static void stubQueryThrows(JdbcTemplate jdbc, RuntimeException ex) {
        when(jdbc.query(anyString(), any(RowMapper.class), any()))
                .thenThrow(ex);
    }

    static void stubUpdate(JdbcTemplate jdbc, int affectedRows) {
        doReturn(affectedRows).when(jdbc).update(anyString(), any(Object[].class));
        doReturn(affectedRows).when(jdbc).update(anyString(), any(Object.class));
        doReturn(affectedRows).when(jdbc).update(anyString(), any(Object.class), any(Object.class));
        doReturn(affectedRows).when(jdbc).update(anyString(), any(Object.class), any(Object.class), any(Object.class));
        doReturn(affectedRows).when(jdbc).update(anyString(), any(Object.class), any(Object.class), any(Object.class), any(Object.class));
        doReturn(affectedRows).when(jdbc).update(anyString(), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class));
        doReturn(affectedRows).when(jdbc).update(anyString(), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class));
    }

    static void stubInsertGeneratedKey(JdbcTemplate jdbc, long generatedId) {
        doAnswer(inv -> {
            KeyHolder keyHolder = inv.getArgument(1);
            Map<String, Object> keyMap = new HashMap<>();
            keyMap.put("id", generatedId);
            keyHolder.getKeyList().add(keyMap);
            return 1;
        }).when(jdbc).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }

    static void stubQueryForObjectScalar(JdbcTemplate jdbc, Object value) {
        when(jdbc.queryForObject(anyString(), any(Class.class), any(Object[].class)))
                .thenReturn(value);
        when(jdbc.queryForObject(anyString(), any(Class.class), any()))
                .thenReturn(value);
    }

    static Timestamp ts(String text) {
        return Timestamp.valueOf(text);
    }

    static MazeSkillActionRepository.MazeSkillActionRow skillActionRow(
            int id, int skillId, int actionType, int order, String paramsJson) {
        return new MazeSkillActionRepository.MazeSkillActionRow(id, skillId, actionType, 0, order, paramsJson);
    }

    static BattleMonsterWaveRepository.WaveConfig waveConfig(
            int id, int stageId, int waveOrder, String monstersJson, int customLevel) {
        return new BattleMonsterWaveRepository.WaveConfig(id, stageId, waveOrder, monstersJson, customLevel);
    }

    private static <T> List<T> mapRows(RowMapper<T> mapper, List<ResultSet> rows) throws SQLException {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<T> out = new java.util.ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            out.add(mapper.mapRow(rows.get(i), i));
        }
        return out;
    }

    private static <T> T mapRow(RowMapper<T> mapper, ResultSet row) throws SQLException {
        return mapper.mapRow(row, 0);
    }

    static final class ResultSetBuilder {
        private final ResultSet rs;

        ResultSetBuilder(ResultSet rs) {
            this.rs = rs;
        }

        ResultSetBuilder intCol(String col, int val) {
            try {
                when(rs.getInt(col)).thenReturn(val);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return this;
        }

        ResultSetBuilder longCol(String col, long val) {
            try {
                when(rs.getLong(col)).thenReturn(val);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return this;
        }

        ResultSetBuilder stringCol(String col, String val) {
            try {
                when(rs.getString(col)).thenReturn(val);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return this;
        }

        ResultSetBuilder timestampCol(String col, Timestamp val) {
            try {
                when(rs.getTimestamp(col)).thenReturn(val);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return this;
        }

        ResultSetBuilder nullableIntCol(String col, Integer val) {
            try {
                if (val == null) {
                    when(rs.getInt(col)).thenReturn(0);
                    when(rs.wasNull()).thenReturn(true);
                } else {
                    when(rs.getInt(col)).thenReturn(val);
                    when(rs.wasNull()).thenReturn(false);
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return this;
        }

        ResultSetBuilder objectCol(String col, Object val) {
            try {
                when(rs.getObject(col)).thenReturn(val);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return this;
        }

        ResultSetBuilder bigDecimalCol(String col, java.math.BigDecimal val) {
            try {
                when(rs.getBigDecimal(col)).thenReturn(val);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return this;
        }
    }
}
