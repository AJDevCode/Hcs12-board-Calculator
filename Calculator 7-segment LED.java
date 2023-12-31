#include <hcs12.h>
#include <dbug12.h>

#include "lcd.h"
#include "keypad.h"
#include "util.h"


/**
 * 7 segments LED decoder
 * 0,1,2,3,4,5,6,7,8,9,A,B,C,D,E,F,G,H
 *
 * Example: if you want to show "1" on LED segments
 * you should do the following:
 * DDRB = 0xff; //set all pin on port b to output
 * PORTB = segment_decoder[1]; //which means one decodes to 0x06:
 * G F E D C B A
 * 0 0 0 0 1 1 0
 *
 *		 A
 * 		----
 * 	   |	| B
 * 	 F |  	|
 * 		--G-        ===> if B and C segments are one we get the shape of 1 (number one)
 * 	   |	| C
 * 	 E |	|
 * 		----
 *       D
 */
unsigned int segment_decoder[]={
                                 0x3f,0x06,0x5b,0x4f,0x66,
                                 0x6d,0x7d,0x07,0x7f,0x6f,
                                 0x77,0x7c,0x39,0x5e,0x79,
                                 0x71,0x3d,0x76
                               };

volatile char message_index_on_7segment_LEDs = 0;
volatile unsigned int counter_for_real_time_interrupt;
volatile unsigned int display_counter = 0;
volatile unsigned int counter_for_real_time_interrupt_limit;

void display_hex_number_on_7segment_LEDs(unsigned int number)
{
  static int index_on_7segment_LEDs = 0;

  //DDRB = 0xff; // PortB is set to be output.
  DDRP = 0xff;

  PTP = ~ (1 << (3 - index_on_7segment_LEDs)); //notice it is negative logic
  PORTB = segment_decoder[( number >> (char) (4*(index_on_7segment_LEDs)) ) & 0xf];

  index_on_7segment_LEDs++;
  /**
   * Index should be 1,2,4,8 ... we shift to left each time
   * example: 0001 << 1 will be: 0010 = 2
   * and 2 = 0010 << 1 will be: 0100 = 4
   * and so on ...
   */

  if (index_on_7segment_LEDs > 3) //means we reach the end of 4 segments LEDs we have
    index_on_7segment_LEDs = 0;

  /**
   * simple example of showing "7" on the first LEDs (the most left one)
   DDRB  = 0xff; // PortB is set to be output.
   DDRP  = 0xff;
   PTP   = ~0x1; //negative logic - means "7" will be shown on first LEDs
   PORTB = 0x07;
   */
}

int MAX = 50; //Varible for how many digits cal can hold
volatile char cal[3][50] = {{'\0'},{'\0'},{'\0'}}; //double array holding both numbers and inbetween the action
volatile int calCount = 0; //keeps track which number we are on
volatile int cntNum = 0; //keeps track which digit we are on for the number

void reset(){ // just returns all values to default
	calCount = 0;
	cntNum = 0;
	cal[0][0] = '\0';
	cal[1][0] = '\0';
	cal[2][0] = '\0';
}

unsigned int StrToInt(char* str){ // converts a str to an int (for calculations)
	int num = 0;
	int i = 0;
	while(str[i] != '\0'){
		num = num * 10;
		num = num + (str[i] - '0');
		i++;
	}
	return num;
}

char* IntToStr(unsigned int num){ // converts int to str (to print out)
	static char str[100];
	if (num < 10){
		str[0] = num + '0';
		str[1] = '\0';
		return str;
	}
	int i = num;
	int x = 1;
	while (i >= 10){
		i = i/10;
		x++;
	}
	str[x] = '\0';
	x--;
	int j;
	for (j = x; j>0; j--){
		str[j] = (num%10) + '0';
		num = num/10;
	}
	str[0] = num + '0';
	return str;
}

volatile char* wrd; // holds string being displayed

volatile unsigned int keypad_debounce_timer = 0;
volatile char keypad_enabled = 'y';

void execute_the_jobs()
{

	if (keypad_enabled == 'y'){
		unsigned char c = KeypadReadPort();
		if(c != KEYPAD_KEY_NONE) {
			DispInit (2, 16); // Initializes screen
			if (c >= '0' && c<= '9' && (calCount == 0 || calCount == 2)){ // gets input for numbers
					cal[calCount][cntNum] = c;
					cal[calCount][cntNum+1] = '\0';
					cntNum ++;
					if (cntNum == (MAX-1)){
						calCount++;
						cntNum = 0;
					}
					wrd = cal[calCount];
			}
			else if((c == 'A' || c == 'B') && calCount <= 1 && cal[0][0] != '\0'){ // gets + or *
					cal[1][0] = c;
					calCount = 2;
					cntNum = 0;
			}
			else if(c == 'C'){ // resets everything
				reset();
				wrd = "reset";
			}
			else if(c == 'D' && cal[2][0] != '\0'){ // gets the total value between the two numbers
				unsigned int tot;
				if (cal[1][0] == 'A') {
					tot = StrToInt(cal[0]) + StrToInt(cal[2]); // converts the str into ints to do the calculations
				}
				else {
					tot = StrToInt(cal[0]) * StrToInt(cal[2]);
				}
				wrd = IntToStr(tot); // reverts the calculation into a string to display
				reset(); // allows the user to put in new numbers without having to press reset
			}
			else {
				wrd = "Error";
			}
			DispClrScr();
			DispStr(1, 1, wrd); // will display whatever action was done at the end

		}

		keypad_debounce_timer ++;
		if (keypad_debounce_timer > 400){
			keypad_debounce_timer = 0;
			keypad_enabled = 'n';
			DisableKeyboard();
		}
	}
	else{
		keypad_debounce_timer ++;
		if (keypad_debounce_timer > 10){
			keypad_debounce_timer = 0;
			keypad_enabled = 'y';
			EnableKeyboardAgain();
		}
	}
}

void INTERRUPT rti_isr(void)
{
  //clear the RTI - don't block the other interrupts
  CRGFLG = 0x80;

  //for instance if limit is "10", every 10 interrupts do something ...
  if (counter_for_real_time_interrupt == counter_for_real_time_interrupt_limit)
    {
      //reset the counter
      counter_for_real_time_interrupt = 0;

      //do some work
      execute_the_jobs();
    }
  else
    counter_for_real_time_interrupt ++;

}

/**
 * initialize the rti: rti_ctl_value will set the pre-scaler ...
 */
void rti_init(unsigned char rti_ctl_value, unsigned int counter_limit)
{
  UserRTI = (unsigned int) & rti_isr; //register the ISR unit

  /**
   * set the maximum limit for the counter:
   * if max set to be 10, every 10 interrupts some work will be done
   */
  counter_for_real_time_interrupt_limit = counter_limit;


  /**
   * RTICTL can be calculated like:
   * i.e: RTICTL == 0x63 == set rate to 16.384 ms:
   * The clock divider is set in register RTICTL and is: (N+1)*2^(M+9),
   * where N is the bit field RTR3 through RTR0  (N is lower bits)
   * 	and M is the bit field RTR6 through RTR4. (M is higher bits)
   * 0110 0011 = 0x63 ==> 1 / (8MHz / 4*2^15)
   * 	which means RTI will happen every 16.384 ms
   * Another example:
   * 0111 1111 = 0x7F ==> 1 / (8MHz / 16*2^16)
   * 	which means RTI will happen every 131.072 ms
   * Another example:
   * 0001 0001 = 0x11 ==> 1 / (8MHz / 2*2^10)   = 256us
   */
  RTICTL = rti_ctl_value;

  // How many times we had RTI interrupts
  counter_for_real_time_interrupt = 0;

  // Enable RTI interrupts
  CRGINT |= 0x80;
  // Clear RTI Flag
  CRGFLG = 0x80;
}




int main(void)
{	
	set_clock_24mhz(); //usually done by D-Bug12 anyway
	DDRB = 0xff;
	rti_init(0x11, 10);
	__asm("cli"); //enable interrupts (maskable and I bit in CCR)

	DDRH = 0x00; //for push buttons
	KeypadInitPort();

	DispInit (2, 16);
	//DispClrScr();
	//DispStr(1, 1, "TEST");


}
