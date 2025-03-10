/*
   LK8000 Tactical Flight Computer -  WWW.LK8000.IT
   Released under GNU/GPL License v.2 or later
   See CREDITS.TXT file for authors and copyrights

*/
#include "options.h"
#include <assert.h>
#include "stl_utils.h"
#include "md5.h"


MD5_Base::MD5_Base()
{
    Init();
}

MD5_Base::MD5_Base(unsigned int key1, unsigned int key2, unsigned int key3, unsigned int key4)
{
  Init(key1, key2, key3, key4);
}

void MD5_Base::Init()
{
  md5_init_ctx(&context);
}

void MD5_Base::Init(unsigned int key1, unsigned int key2, unsigned int key3, unsigned int key4)
{
  md5_init_ctx(&context);
  context.A = key1;
  context.B = key2;
  context.C = key3;
  context.D = key4;
}

// MD5 block update operation. Continues an MD5 message-digest
// operation, processing another message block, and updating the
// context.
void MD5_Base::Update(const void* input, size_t size)
{
  md5_process_bytes(input, size, &context);
}

// MD5 finalization. Ends an MD5 message-digest operation,
// zeroizing the context and return the message digest.
std::string MD5::Final()
{
  static constexpr char Digit[] = {
      '0', '1', '2', '3', '4', '5', '6', '7',
      '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

  unsigned char resbuf[16];
  md5_finish_ctx(&context, resbuf);

  std::string out;
  out.reserve(32);
  auto out_iterator = std::back_inserter(out);
  for ( char c : resbuf) {
    out_iterator = Digit[(c & 0xF0) >> 4];
    out_iterator = Digit[(c & 0x0F) >> 0];
  }
  return out;
}

#ifndef DOCTEST_CONFIG_DISABLE
#include <doctest/doctest.h>

TEST_CASE("ValidFrequency") {
  MD5_Base hash;
  CHECK_EQ(MD5(hash).Final(), "d41d8cd98f00b204e9800998ecf8427e");

  hash.Update(std::string("test"));
  CHECK_EQ(MD5(hash).Final(), "098f6bcd4621d373cade4e832627b4f6");

  hash.Update(std::string("123"));
  CHECK_EQ(MD5(hash).Final(), "cc03e747a6afbbcbf8be7668acfebee5");

  hash.Update('4');
  CHECK_EQ(MD5(hash).Final(), "16d7a4fca7442dda3ad93c9a726597e4");
}

#endif
