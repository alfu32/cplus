#include <errno.h>
#include <stddef.h>
#include <string.h>

comptime import "../strings/string.cp";

@test "string init and empty invariants" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.size() == 0);
    CPLUS_TEST_ASSERT(value.capacity_of() == 0);
    CPLUS_TEST_ASSERT(value.empty());
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "") == 0);
    CPLUS_TEST_ASSERT(value.strlen() == 0);
    value.destroy();
}

@test "string assign reserve clear destroy" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.assign("hello") == STRING_OK);
    CPLUS_TEST_ASSERT(value.size() == 5);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "hello") == 0);
    CPLUS_TEST_ASSERT(value.capacity_of() >= 5);
    size_t old_capacity = value.capacity_of();
    CPLUS_TEST_ASSERT(value.reserve(old_capacity + 20) == STRING_OK);
    CPLUS_TEST_ASSERT(value.capacity_of() >= old_capacity + 20);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "hello") == 0);
    value.clear();
    CPLUS_TEST_ASSERT(value.empty());
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "") == 0);
    value.destroy();
    CPLUS_TEST_ASSERT(value.data == NULL);
    CPLUS_TEST_ASSERT(value.size() == 0);
    CPLUS_TEST_ASSERT(value.capacity_of() == 0);
}

@test "string assign n truncates and terminates" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.assign_n("abcdef", 3) == STRING_OK);
    CPLUS_TEST_ASSERT(value.size() == 3);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "abc") == 0);
    CPLUS_TEST_ASSERT(value.assign_n("xy", 99) == STRING_OK);
    CPLUS_TEST_ASSERT(value.size() == 2);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "xy") == 0);
    value.destroy();
}

@test "string append variants" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.assign("ab") == STRING_OK);
    CPLUS_TEST_ASSERT(value.append("cd") == STRING_OK);
    CPLUS_TEST_ASSERT(value.append_n("EFGH", 2) == STRING_OK);
    CPLUS_TEST_ASSERT(value.append_char('!') == STRING_OK);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "abcdEF!") == 0);
    CPLUS_TEST_ASSERT(value.size() == 7);
    value.destroy();
}

@test "string strcpy strncpy strcat strncat facade" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.strcpy("alpha") == STRING_OK);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "alpha") == 0);
    CPLUS_TEST_ASSERT(value.strcat("-beta") == STRING_OK);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "alpha-beta") == 0);
    CPLUS_TEST_ASSERT(value.strncpy("123456", 4) == STRING_OK);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "1234") == 0);
    CPLUS_TEST_ASSERT(value.strncat("abcdef", 3) == STRING_OK);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "1234abc") == 0);
    value.destroy();
}

@test "string comparison facade" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.assign("alphabet") == STRING_OK);
    CPLUS_TEST_ASSERT(value.strcmp("alphabet") == 0);
    CPLUS_TEST_ASSERT(value.strcmp("alphaz") < 0);
    CPLUS_TEST_ASSERT(value.strncmp("alphaZZ", 5) == 0);
    CPLUS_TEST_ASSERT(value.strcoll("alphabet") == 0);
    value.destroy();
}

@test "string search facade" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.assign("abc:def:ghi") == STRING_OK);
    const char* first = value.strchr(':');
    const char* last = value.strrchr(':');
    const char* sub = value.strstr("def");
    const char* any = value.strpbrk("xyz:g");
    CPLUS_TEST_ASSERT(first != NULL && strcmp(first, ":def:ghi") == 0);
    CPLUS_TEST_ASSERT(last != NULL && strcmp(last, ":ghi") == 0);
    CPLUS_TEST_ASSERT(sub != NULL && strcmp(sub, "def:ghi") == 0);
    CPLUS_TEST_ASSERT(any != NULL && *any == ':');
    value.destroy();
}

@test "string span facade" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.assign("aaab12") == STRING_OK);
    CPLUS_TEST_ASSERT(value.strspn("ab") == 4);
    CPLUS_TEST_ASSERT(value.strcspn("12") == 4);
    value.destroy();
}

@test "string strxfrm facade" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.strxfrm("locale-text") == STRING_OK);
    CPLUS_TEST_ASSERT(value.size() == strlen(value.c_str()));
    CPLUS_TEST_ASSERT(value.strcoll("locale-text") == 0);
    value.destroy();
}

@test "string raw memory facade" {
    char source[8] = "abcdef";
    char destination[8];
    CPLUS_TEST_ASSERT(string.memset(destination, 0, sizeof(destination)) == destination);
    CPLUS_TEST_ASSERT(string.memcpy(destination, source, 7) == destination);
    CPLUS_TEST_ASSERT(strcmp(destination, "abcdef") == 0);
    CPLUS_TEST_ASSERT(string.memcmp(destination, source, 7) == 0);
    CPLUS_TEST_ASSERT(string.memchr(destination, 'd', 7) == &destination[3]);
    CPLUS_TEST_ASSERT(string.memmove(destination + 1, destination, 5) == destination + 1);
    CPLUS_TEST_ASSERT(destination[1] == 'a');
    CPLUS_TEST_ASSERT(destination[5] == 'e');
}

@test "string strerror facade" {
    char* message = string.strerror(EINVAL);
    CPLUS_TEST_ASSERT(message != NULL);
    CPLUS_TEST_ASSERT(strlen(message) > 0);
}

@test "string strtok facade uses raw caller buffer" {
    char buffer[32] = "one,two,three";
    char* first = string.strtok(buffer, ",");
    char* second = string.strtok(NULL, ",");
    char* third = string.strtok(NULL, ",");
    CPLUS_TEST_ASSERT(first != NULL && strcmp(first, "one") == 0);
    CPLUS_TEST_ASSERT(second != NULL && strcmp(second, "two") == 0);
    CPLUS_TEST_ASSERT(third != NULL && strcmp(third, "three") == 0);
    CPLUS_TEST_ASSERT(string.strtok(NULL, ",") == NULL);
}

@test "string indent all logical lines" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.assign("alpha\nbeta\n") == STRING_OK);
    CPLUS_TEST_ASSERT(value.indent(3) == STRING_OK);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "   alpha\n   beta\n") == 0);
    CPLUS_TEST_ASSERT(value.size() == strlen("   alpha\n   beta\n"));
    value.destroy();
}

@test "string indent handles blank lines" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.assign("a\n\nb") == STRING_OK);
    CPLUS_TEST_ASSERT(value.indent(2) == STRING_OK);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "  a\n  \n  b") == 0);
    value.destroy();
}

@test "string dedent removes up to requested spaces" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.assign("    alpha\n  beta\n      gamma") == STRING_OK);
    CPLUS_TEST_ASSERT(value.dedent(3) == STRING_OK);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), " alpha\nbeta\n   gamma") == 0);
    value.destroy();
}

@test "string trim indent removes common nonblank indent" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.assign("    alpha\n      beta\n\n    gamma") == STRING_OK);
    CPLUS_TEST_ASSERT(string.trim_indent(&value) == STRING_OK);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "alpha\n  beta\n\ngamma") == 0);
    value.destroy();
}

@test "string trim indent ignores blank line spaces" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.assign("    alpha\n \n      beta") == STRING_OK);
    CPLUS_TEST_ASSERT(string.trim_indent(&value) == STRING_OK);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "alpha\n\n  beta") == 0);
    value.destroy();
}

@test "string zero indentation operations are noops" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.assign("x\ny") == STRING_OK);
    CPLUS_TEST_ASSERT(value.indent(0) == STRING_OK);
    CPLUS_TEST_ASSERT(value.dedent(0) == STRING_OK);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "x\ny") == 0);
    value.destroy();
}

@test "string negative indentation is rejected without mutation" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.assign("text") == STRING_OK);
    CPLUS_TEST_ASSERT(value.indent(-1) == STRING_ERROR_INVALID_ARGUMENT);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "text") == 0);
    CPLUS_TEST_ASSERT(value.dedent(-1) == STRING_ERROR_INVALID_ARGUMENT);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "text") == 0);
    value.destroy();
}

@test "string null argument validation" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.assign(NULL) == STRING_ERROR_INVALID_ARGUMENT);
    CPLUS_TEST_ASSERT(value.append(NULL) == STRING_ERROR_INVALID_ARGUMENT);
    CPLUS_TEST_ASSERT(value.strcpy(NULL) == STRING_ERROR_INVALID_ARGUMENT);
    CPLUS_TEST_ASSERT(value.strncpy(NULL, 1) == STRING_ERROR_INVALID_ARGUMENT);
    CPLUS_TEST_ASSERT(value.strcat(NULL) == STRING_ERROR_INVALID_ARGUMENT);
    CPLUS_TEST_ASSERT(value.strncat(NULL, 1) == STRING_ERROR_INVALID_ARGUMENT);
    value.destroy();
}

@test "string capacity reuse across smaller assignments" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.assign("this is a longer initial string") == STRING_OK);
    size_t capacity = value.capacity_of();
    CPLUS_TEST_ASSERT(value.assign("tiny") == STRING_OK);
    CPLUS_TEST_ASSERT(value.capacity_of() == capacity);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "tiny") == 0);
    value.destroy();
}

@test "string empty source operations allocate a valid terminator" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.assign("") == STRING_OK);
    CPLUS_TEST_ASSERT(value.data != NULL);
    CPLUS_TEST_ASSERT(value.size() == 0);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "") == 0);
    value.destroy();

    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.strcpy("") == STRING_OK);
    CPLUS_TEST_ASSERT(value.data != NULL);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "") == 0);
    CPLUS_TEST_ASSERT(value.append("") == STRING_OK);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "") == 0);
    value.destroy();
}

@test "string assignment supports internal substrings" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.assign("abcdef") == STRING_OK);
    const char* suffix = value.c_str() + 2;
    CPLUS_TEST_ASSERT(value.assign(suffix) == STRING_OK);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "cdef") == 0);
    value.destroy();
}

@test "string append supports self alias and growth" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.assign("abc") == STRING_OK);
    CPLUS_TEST_ASSERT(value.append(value.c_str()) == STRING_OK);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "abcabc") == 0);
    CPLUS_TEST_ASSERT(value.append_n(value.c_str() + 1, 2) == STRING_OK);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "abcabcbc") == 0);
    value.destroy();
}

@test "string strcat facade supports self alias" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.strcpy("xy") == STRING_OK);
    CPLUS_TEST_ASSERT(value.strcat(value.c_str()) == STRING_OK);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "xyxy") == 0);
    CPLUS_TEST_ASSERT(value.strncat(value.c_str() + 1, 2) == STRING_OK);
    CPLUS_TEST_ASSERT(strcmp(value.c_str(), "xyxyyx") == 0);
    value.destroy();
}

@test "string strxfrm supports own buffer as source" {
    string value;
    CPLUS_TEST_ASSERT(value.init() == STRING_OK);
    CPLUS_TEST_ASSERT(value.assign("transform-me") == STRING_OK);
    CPLUS_TEST_ASSERT(value.strxfrm(value.c_str()) == STRING_OK);
    CPLUS_TEST_ASSERT(value.size() == strlen(value.c_str()));
    value.destroy();
}

@test "string static trim indent validates null" {
    CPLUS_TEST_ASSERT(string.trim_indent(NULL) == STRING_ERROR_INVALID_ARGUMENT);
}
