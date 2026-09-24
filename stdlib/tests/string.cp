#include <errno.h>
#include <stddef.h>
#include <string.h>

comptime import "stdlib:/strings/string.cp";
comptime import "stdlib:/containers/dynamic_list.cp";

comptime typedef dynamic_list(string) owned_string_list_t;
comptime typedef dynamic_list(str_t) str_view_list_t;

borrowed const char* owned_string_key(borrowed const string* item) {
    return item->data == NULL ? "" : item->data;
}

borrowed const char* string_view_key(borrowed const str_t* item) {
    return item->data == NULL ? "" : item->data;
}

@test "string init and empty invariants" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.size() == 0);
    @assert(value.capacity_of() == 0);
    @assert(value.empty());
    @assert(strcmp(value.c_str(), "") == 0);
    str_t view = value.as_str();
    @assert(view.strlen() == 0);
    value.destroy();
}

@test "collections sort string and borrowed-view keys without copying keys" {
    owned_string_list_t owned_values;
    string zulu;
    string alpha;
    @assert(zulu.init() == 0);
    @assert(alpha.init() == 0);
    @assert(zulu.assign("zulu") == 0);
    @assert(alpha.assign("alpha") == 0);
    @assert(owned_values.init() == 0);
    @assert(owned_values.push(&zulu) == 0);
    @assert(owned_values.push(&alpha) == 0);
    @assert(owned_values.orderByAlphaumeric(owned_string_key) == 0);
    @assert(strcmp(owned_values.items[0].data, "alpha") == 0);
    for (size_t i = 0; i < owned_values.length; i++) string__destroy(&owned_values.items[i]);
    owned_values.destroy();

    str_view_list_t views;
    str_t zulu_view = str_t.from("zulu");
    str_t alpha_view = str_t.from("alpha");
    @assert(views.init() == 0);
    @assert(views.push(&zulu_view) == 0);
    @assert(views.push(&alpha_view) == 0);
    @assert(views.orderByAlphaumeric(string_view_key) == 0);
    @assert(strcmp(views.get(0)->data, "alpha") == 0);
    views.destroy();
}

@test "string assign reserve clear destroy" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("hello") == STRING_OK);
    @assert(value.size() == 5);
    @assert(strcmp(value.c_str(), "hello") == 0);
    @assert(value.capacity_of() >= 5);
    size_t old_capacity = value.capacity_of();
    @assert(value.reserve(old_capacity + 20) == STRING_OK);
    @assert(value.capacity_of() >= old_capacity + 20);
    @assert(strcmp(value.c_str(), "hello") == 0);
    value.clear();
    @assert(value.empty());
    @assert(strcmp(value.c_str(), "") == 0);
    value.destroy();
    @assert(value.data == NULL);
    @assert(value.size() == 0);
    @assert(value.capacity_of() == 0);
}

@test "string assign n truncates and terminates" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign_n("abcdef", 3) == STRING_OK);
    @assert(value.size() == 3);
    @assert(strcmp(value.c_str(), "abc") == 0);
    @assert(value.assign_n("xy", 99) == STRING_OK);
    @assert(value.size() == 2);
    @assert(strcmp(value.c_str(), "xy") == 0);
    value.destroy();
}

@test "string append variants" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("ab") == STRING_OK);
    @assert(value.append("cd") == STRING_OK);
    @assert(value.append_n("EFGH", 2) == STRING_OK);
    @assert(value.append_char('!') == STRING_OK);
    @assert(strcmp(value.c_str(), "abcdEF!") == 0);
    @assert(value.size() == 7);
    value.destroy();
}

@test "string assignment and append APIs" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("alpha") == STRING_OK);
    @assert(strcmp(value.c_str(), "alpha") == 0);
    @assert(value.append("-beta") == STRING_OK);
    @assert(strcmp(value.c_str(), "alpha-beta") == 0);
    @assert(value.assign_n("123456", 4) == STRING_OK);
    @assert(strcmp(value.c_str(), "1234") == 0);
    @assert(value.append_n("abcdef", 3) == STRING_OK);
    @assert(strcmp(value.c_str(), "1234abc") == 0);
    value.destroy();
}

@test "string comparison facade" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("alphabet") == STRING_OK);
    str_t view = value.as_str();
    @assert(view.strcmp("alphabet") == 0);
    @assert(view.strcmp("alphaz") < 0);
    @assert(view.strncmp("alphaZZ", 5) == 0);
    @assert(view.strcoll("alphabet") == 0);
    value.destroy();
}

@test "string search facade" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("abc:def:ghi") == STRING_OK);
    str_t view = value.as_str();
    const char* first = view.strchr(':');
    const char* last = view.strrchr(':');
    const char* sub = view.strstr("def");
    const char* any = view.strpbrk("xyz:g");
    @assert(first != NULL && strcmp(first, ":def:ghi") == 0);
    @assert(last != NULL && strcmp(last, ":ghi") == 0);
    @assert(sub != NULL && strcmp(sub, "def:ghi") == 0);
    @assert(any != NULL && *any == ':');
    value.destroy();
}

@test "string span facade" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("aaab12") == STRING_OK);
    str_t view = value.as_str();
    @assert(view.strspn("ab") == 4);
    @assert(view.strcspn("12") == 4);
    value.destroy();
}

@test "string collation key and str view" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.transform_collation_key("locale-text") == STRING_OK);
    @assert(value.size() == strlen(value.c_str()));
    str_t view = value.as_str();
    @assert(view.strcoll("locale-text") == 0);
    value.destroy();
}

@test "str view raw memory utilities" {
    char source[8] = "abcdef";
    char destination[8];
    @assert(str_t.memset(destination, 0, sizeof(destination)) == destination);
    @assert(str_t.memcpy(destination, source, 7) == destination);
    @assert(strcmp(destination, "abcdef") == 0);
    @assert(str_t.memcmp(destination, source, 7) == 0);
    @assert(str_t.memchr(destination, 'd', 7) == &destination[3]);
    @assert(str_t.memmove(destination + 1, destination, 5) == destination + 1);
    @assert(destination[1] == 'a');
    @assert(destination[5] == 'e');
}

@test "str view strerror helper" {
    char* message = str_t.strerror(EINVAL);
    @assert(message != NULL);
    @assert(strlen(message) > 0);
}

@test "str view tokenizer uses raw caller buffer" {
    char buffer[32] = "one,two,three";
    char* first = str_t.strtok(buffer, ",");
    char* second = str_t.strtok(NULL, ",");
    char* third = str_t.strtok(NULL, ",");
    @assert(first != NULL && strcmp(first, "one") == 0);
    @assert(second != NULL && strcmp(second, "two") == 0);
    @assert(third != NULL && strcmp(third, "three") == 0);
    @assert(str_t.strtok(NULL, ",") == NULL);
}

@test "string indent all logical lines" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("alpha\nbeta\n") == STRING_OK);
    @assert(value.indent(3) == STRING_OK);
    @assert(strcmp(value.c_str(), "   alpha\n   beta\n") == 0);
    @assert(value.size() == strlen("   alpha\n   beta\n"));
    value.destroy();
}

@test "string indent handles blank lines" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("a\n\nb") == STRING_OK);
    @assert(value.indent(2) == STRING_OK);
    @assert(strcmp(value.c_str(), "  a\n  \n  b") == 0);
    value.destroy();
}

@test "string dedent removes up to requested spaces" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("    alpha\n  beta\n      gamma") == STRING_OK);
    @assert(value.dedent(3) == STRING_OK);
    @assert(strcmp(value.c_str(), " alpha\nbeta\n   gamma") == 0);
    value.destroy();
}

@test "string trim indent removes common nonblank indent" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("    alpha\n      beta\n\n    gamma") == STRING_OK);
    @assert(value.trim_indent() == STRING_OK);
    @assert(strcmp(value.c_str(), "alpha\n  beta\n\ngamma") == 0);
    value.destroy();
}

@test "string trim indent ignores blank line spaces" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("    alpha\n \n      beta") == STRING_OK);
    @assert(value.trim_indent() == STRING_OK);
    @assert(strcmp(value.c_str(), "alpha\n\n  beta") == 0);
    value.destroy();
}

@test "string zero indentation operations are noops" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("x\ny") == STRING_OK);
    @assert(value.indent(0) == STRING_OK);
    @assert(value.dedent(0) == STRING_OK);
    @assert(strcmp(value.c_str(), "x\ny") == 0);
    value.destroy();
}

@test "string negative indentation is rejected without mutation" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("text") == STRING_OK);
    @assert(value.indent(-1) == STRING_ERROR_INVALID_ARGUMENT);
    @assert(strcmp(value.c_str(), "text") == 0);
    @assert(value.dedent(-1) == STRING_ERROR_INVALID_ARGUMENT);
    @assert(strcmp(value.c_str(), "text") == 0);
    value.destroy();
}

@test "string null argument validation" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign(NULL) == STRING_ERROR_INVALID_ARGUMENT);
    @assert(value.append(NULL) == STRING_ERROR_INVALID_ARGUMENT);
    @assert(value.assign_n(NULL, 1) == STRING_ERROR_INVALID_ARGUMENT);
    @assert(value.append_n(NULL, 1) == STRING_ERROR_INVALID_ARGUMENT);
    value.destroy();
}

@test "string capacity reuse across smaller assignments" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("this is a longer initial string") == STRING_OK);
    size_t capacity = value.capacity_of();
    @assert(value.assign("tiny") == STRING_OK);
    @assert(value.capacity_of() == capacity);
    @assert(strcmp(value.c_str(), "tiny") == 0);
    value.destroy();
}

@test "string empty source operations allocate a valid terminator" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("") == STRING_OK);
    @assert(value.data != NULL);
    @assert(value.size() == 0);
    @assert(strcmp(value.c_str(), "") == 0);
    value.destroy();

    @assert(value.init() == STRING_OK);
    @assert(value.assign("") == STRING_OK);
    @assert(value.data != NULL);
    @assert(strcmp(value.c_str(), "") == 0);
    @assert(value.append("") == STRING_OK);
    @assert(strcmp(value.c_str(), "") == 0);
    value.destroy();
}

@test "string assignment supports internal substrings" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("abcdef") == STRING_OK);
    const char* suffix = value.c_str() + 2;
    @assert(value.assign(suffix) == STRING_OK);
    @assert(strcmp(value.c_str(), "cdef") == 0);
    value.destroy();
}

@test "string append supports self alias and growth" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("abc") == STRING_OK);
    @assert(value.append(value.c_str()) == STRING_OK);
    @assert(strcmp(value.c_str(), "abcabc") == 0);
    @assert(value.append_n(value.c_str() + 1, 2) == STRING_OK);
    @assert(strcmp(value.c_str(), "abcabcbc") == 0);
    value.destroy();
}

@test "string append supports self alias" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("xy") == STRING_OK);
    @assert(value.append(value.c_str()) == STRING_OK);
    @assert(strcmp(value.c_str(), "xyxy") == 0);
    @assert(value.append_n(value.c_str() + 1, 2) == STRING_OK);
    @assert(strcmp(value.c_str(), "xyxyyx") == 0);
    value.destroy();
}

@test "string collation transform supports own buffer as source" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("transform-me") == STRING_OK);
    @assert(value.transform_collation_key(value.c_str()) == STRING_OK);
    @assert(value.size() == strlen(value.c_str()));
    value.destroy();
}

@test "string trim indent validates null receiver" {
    @assert(string.trim_indent(NULL) == STRING_ERROR_INVALID_ARGUMENT);
}
