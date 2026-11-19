package com.growercare.grower.adapters.in.web;

/** Test helper: the package-private ContactView's toString, for a test in another package. */
public final class ContactViewProbe {

    public String view(String phone) {
        return new InternalGrowerController.ContactView(phone, "mr", "ACTIVE").toString();
    }
}
