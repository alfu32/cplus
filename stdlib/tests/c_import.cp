@import("fixtures/imported_plain_c.c")

@test "@import includes unchanged C source" {
    imported_plain_value value = (imported_plain_value){73};
    @assertEquals(73, imported_plain_value_read(&value))
}
