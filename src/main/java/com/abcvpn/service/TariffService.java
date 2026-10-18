package com.abcvpn.service;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import com.abcvpn.entity.Tariff;
import com.abcvpn.exception.ErrorCode;
import com.abcvpn.exception.FlowException;
import com.abcvpn.repository.TariffRepository;

@Service
public class TariffService {
    private final TariffRepository tariffRepository;
    public TariffService(TariffRepository tariffRepository) { this.tariffRepository = tariffRepository; }
    public List<Tariff> enabled() { return tariffRepository.findEnabled(); }
    public List<Tariff> referralWalletTariffs() {
        return List.of(requireCode("REFERRAL_1D"),requireCode("REFERRAL_7D"),requireCode("BASIC"));
    }
    public Tariff require(long id) { return tariffRepository.findById(id).orElseThrow(()->new FlowException(ErrorCode.TARIFF_NOT_FOUND)); }
    public Tariff requirePurchasable(long id) {
        Tariff tariff = tariffRepository.findById(id).orElseThrow(() -> new FlowException(ErrorCode.TARIFF_NOT_FOUND));
        if (!tariff.enabled() || "TRIAL".equals(tariff.code())) throw new FlowException(ErrorCode.TARIFF_NOT_FOUND);
        return tariff;
    }
    public Tariff requireWalletPurchasable(long id) {
        Tariff tariff=require(id);
        if(!Set.of("REFERRAL_1D","REFERRAL_7D","BASIC").contains(tariff.code()))
            throw new FlowException(ErrorCode.TARIFF_NOT_FOUND);
        return tariff;
    }
    private Tariff requireCode(String code) {
        return tariffRepository.findByCode(code).orElseThrow(()->new FlowException(ErrorCode.TARIFF_NOT_FOUND));
    }
}
