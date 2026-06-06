package com.aquariux.technical.assessment.trade.mapper;

import com.aquariux.technical.assessment.trade.dto.internal.UserWalletDto;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface UserWalletMapper {

    @Select("""
            SELECT s.symbol, s.name, uw.balance
            FROM symbols s
            INNER JOIN user_wallets uw ON s.id = uw.symbol_id AND uw.user_id = #{userId}
            ORDER BY s.symbol
            """)
    List<UserWalletDto> findByUserId(Long userId);

    @Select("""
            SELECT uw.id, uw.user_id as userId, uw.symbol_id as symbolId, uw.balance, uw.updated_at as updatedAt,
                   s.symbol, s.name
            FROM user_wallets uw
            INNER JOIN symbols s ON uw.symbol_id = s.id
            WHERE uw.user_id = #{userId} AND uw.symbol_id = #{symbolId}
            """)
    UserWalletDto findByUserIdAndSymbolId(@Param("userId") Long userId, @Param("symbolId") Long symbolId);

    @Update("""
            UPDATE user_wallets SET balance = #{balance}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{walletId}
            """)
    void updateBalance(@Param("walletId") Long walletId, @Param("balance") BigDecimal balance);

    @Insert("""
            INSERT INTO user_wallets (user_id, symbol_id, balance)
            VALUES (#{userId}, #{symbolId}, #{balance})
            """)
    void insertWallet(@Param("userId") Long userId, @Param("symbolId") Long symbolId, @Param("balance") BigDecimal balance);
}