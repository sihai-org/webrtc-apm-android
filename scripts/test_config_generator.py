import unittest
from generate_config import parse_body, leaves


class ConfigExtractionTest(unittest.TestCase):
    def test_member_functions_do_not_hide_fields(self):
        body = '''
        struct Group {
          bool operator==(const Group& rhs) const;
          bool operator!=(const Group& rhs) const { return !(*this == rhs); }
          bool enabled = false;
          enum class Mode { kFirst, kSecond };
          Mode mode = Mode::kSecond;
        } group;
        '''
        fields = list(leaves(parse_body(body)))
        self.assertEqual(['group.enabled', 'group.mode'], [name for name, _ in fields])
        self.assertEqual(['kFirst', 'kSecond'], fields[1][1]['enum'])

    def test_reused_aggregate_and_constructor_initializers(self):
        fields = parse_body('''
        struct Pair { float low; float high; };
        Pair first = {.low = 1.f, .high = 2.f};
        Pair second = Pair(3.f, 4.f);
        ''')
        self.assertEqual(['first.low', 'first.high', 'second.low', 'second.high'],
                         [name for name, _ in leaves(fields)])

    def test_unknown_fields_fail_instead_of_being_dropped(self):
        with self.assertRaises(ValueError): parse_body('std::array<float, 5> coefficients;')
        with self.assertRaises(ValueError): parse_body('enum Mode { kA = 10, kB }; Mode mode;')


if __name__ == '__main__': unittest.main()
