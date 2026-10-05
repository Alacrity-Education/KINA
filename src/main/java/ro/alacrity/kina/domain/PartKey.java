package ro.alacrity.kina.domain;

import lombok.experimental.UtilityClass;

/** Builds the cross-distributor part key used by rankers and caches: {@code distributor + ":" + partNumber}. */
@UtilityClass
public class PartKey {

    public static String of(Distributor distributor, String distributorPartNumber) {
        return distributor.name() + ":" + distributorPartNumber;
    }

    public static String of(Part part) {
        return of(part.distributor(), part.distributorPartNumber());
    }
}
