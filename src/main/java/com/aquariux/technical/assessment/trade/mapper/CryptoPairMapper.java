package com.aquariux.technical.assessment.trade.mapper;

import com.aquariux.technical.assessment.trade.entity.CryptoPair;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CryptoPairMapper {

    @Select("""
            SELECT id FROM crypto_pairs WHERE pair_name = #{pairName}
            """)
    Long findIdByPairName(String pairName);

    @Select("""
            SELECT id, base_symbol_id as baseSymbolId, quote_symbol_id as quoteSymbolId,
                   pair_name as pairName, active
            FROM crypto_pairs
            WHERE pair_name = #{pairName}
            """)
    CryptoPair findByPairName(String pairName);
}